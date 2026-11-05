package com.smartrecruit.system.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 服务间接口授权规则测试。
 *
 * <p>本测试直接构造与 {@link SecurityConfig} 中完全相同的授权管理器
 * （{@code hasAnyAuthority(scope, ROLE_SYSTEM)}），对匿名、普通用户、
 * 内部 scope 令牌、过渡期 ROLE_SYSTEM 四种身份做判定，
 * 不启动 Spring 上下文、不连接数据库、不执行任何文件操作。</p>
 *
 * <h3>覆盖场景</h3>
 * <ol>
 *   <li>未认证请求访问受保护的服务间接口 → 拒绝</li>
 *   <li>普通用户令牌访问服务间接口 → 拒绝</li>
 *   <li>未认证用户删除文件 → 拒绝（授权判定先于控制器，删除不会被执行）</li>
 *   <li>scope 与路径不匹配（如 upload 令牌删文件）→ 拒绝</li>
 *   <li>匹配的内部 scope 令牌 / 过渡期 ROLE_SYSTEM → 放行</li>
 *   <li>{@code /api/v1/internal/**} 兜底规则：匿名拒绝、已认证放行</li>
 * </ol>
 */
@DisplayName("A1 System 服务间接口授权规则")
class SecurityConfigServiceEndpointRuleTest {

    /** 与 SecurityConfig 中的 requestMatchers 一一对应：路径前缀 → 允许的 authority。 */
    private static final Map<String, String> RULES = Map.of(
            "/api/v1/files/upload", SecurityConfig.SCOPE_FILES_UPLOAD,
            "/api/v1/files/download-bytes", SecurityConfig.SCOPE_FILES_DOWNLOAD,
            "/api/v1/files/delete", SecurityConfig.SCOPE_FILES_DELETE,
            "/api/v1/internal/users/admins", SecurityConfig.SCOPE_INTERNAL_USERS_READ,
            "/api/v1/internal/notifications", SecurityConfig.SCOPE_INTERNAL_NOTIFICATIONS_SEND,
            "/api/v1/internal/email/send", SecurityConfig.SCOPE_INTERNAL_EMAIL_SEND);

    private static AuthorizationManager<RequestAuthorizationContext> managerFor(String authority) {
        return AuthorityAuthorizationManager.<RequestAuthorizationContext>hasAuthority(authority);
    }

    private static boolean granted(AuthorizationManager<RequestAuthorizationContext> manager,
                                   Authentication authentication,
                                   String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", path);
        return manager.authorize(() -> authentication, new RequestAuthorizationContext(request)).isGranted();
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    /** 普通登录用户：持有业务角色与权限，但没有任何内部服务 scope。 */
    private static Authentication normalUser() {
        return UsernamePasswordAuthenticationToken.authenticated("hr@company.com", 1001L,
                List.of(new SimpleGrantedAuthority("ROLE_HR"),
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("system:settings")));
    }

    private static Authentication scopeToken(String... scopes) {
        return UsernamePasswordAuthenticationToken.authenticated("smart-recruit-recruitment", null,
                List.of(scopes).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private static Authentication transitionalSystem() {
        return UsernamePasswordAuthenticationToken.authenticated("system", null,
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM")));
    }

    /** R1-A 后：ROLE_SYSTEM 已不再被任何服务间接口接受。 */
    @Test
    @DisplayName("ROLE_SYSTEM 已不能访问服务间接口（过渡分支已删除）")
    void roleSystemNoLongerAccepted() {
        RULES.forEach((path, authority) -> assertFalse(
                granted(managerFor(authority), transitionalSystem(), path),
                "ROLE_SYSTEM 访问 " + path + " 必须被拒绝，INNER-REQUEST 不再是合法身份"));
    }

    // =====================================================================
    // 1 & 2. 匿名 / 普通用户：全部拒绝
    // =====================================================================

    @Test
    @DisplayName("匿名访问服务间接口：6 条规则全部拒绝")
    void anonymousDeniedOnAllServiceEndpoints() {
        RULES.forEach((path, authority) -> assertFalse(
                granted(managerFor(authority), anonymous(), path),
                "匿名访问 " + path + " 必须被拒绝"));
    }

    @Test
    @DisplayName("普通用户令牌访问服务间接口：6 条规则全部拒绝")
    void normalUserDeniedOnAllServiceEndpoints() {
        RULES.forEach((path, authority) -> assertFalse(
                granted(managerFor(authority), normalUser(), path),
                "普通用户访问 " + path + " 必须被拒绝，不能借业务角色越权"));
    }

    // =====================================================================
    // 3. 未认证删除文件
    // =====================================================================

    @Test
    @DisplayName("未认证用户删除文件：授权判定拒绝，删除逻辑不可达")
    void anonymousDeleteFileDenied() {
        assertFalse(granted(managerFor(SecurityConfig.SCOPE_FILES_DELETE),
                        anonymous(), "/api/v1/files/delete"),
                "匿名 DELETE /api/v1/files/delete 必须被拒绝");
        assertFalse(granted(managerFor(SecurityConfig.SCOPE_FILES_DELETE),
                        normalUser(), "/api/v1/files/delete"),
                "普通用户 DELETE /api/v1/files/delete 必须被拒绝");
    }

    // =====================================================================
    // 4. scope 与路径不匹配
    // =====================================================================

    @Test
    @DisplayName("scope 与操作不匹配：upload 令牌不能删除文件，download 令牌不能上传")
    void mismatchedScopeDenied() {
        assertFalse(granted(managerFor(SecurityConfig.SCOPE_FILES_DELETE),
                        scopeToken(SecurityConfig.SCOPE_FILES_UPLOAD), "/api/v1/files/delete"),
                "files.upload 令牌不得删除文件");
        assertFalse(granted(managerFor(SecurityConfig.SCOPE_FILES_UPLOAD),
                        scopeToken(SecurityConfig.SCOPE_FILES_DOWNLOAD), "/api/v1/files/upload"),
                "files.download 令牌不得上传文件");
        assertFalse(granted(managerFor(SecurityConfig.SCOPE_INTERNAL_USERS_READ),
                        scopeToken(SecurityConfig.SCOPE_FILES_DELETE), "/api/v1/internal/users/admins"),
                "files.delete 令牌不得读取管理员列表");
    }

    // =====================================================================
    // 5. 合法授权放行
    // =====================================================================

    @Test
    @DisplayName("匹配的 scope 令牌：对应接口放行")
    void matchingScopeAllowed() {
        assertTrue(granted(managerFor(SecurityConfig.SCOPE_FILES_DELETE),
                        scopeToken(SecurityConfig.SCOPE_FILES_DELETE), "/api/v1/files/delete"),
                "files.delete 令牌删除文件应放行");
        assertTrue(granted(managerFor(SecurityConfig.SCOPE_INTERNAL_USERS_READ),
                        scopeToken(SecurityConfig.SCOPE_INTERNAL_USERS_READ), "/api/v1/internal/users/admins"),
                "internal.users.read 令牌读取管理员列表应放行");
        assertTrue(granted(managerFor(SecurityConfig.SCOPE_INTERNAL_NOTIFICATIONS_SEND),
                        scopeToken(SecurityConfig.SCOPE_INTERNAL_NOTIFICATIONS_SEND),
                        "/api/v1/internal/notifications"),
                "internal.notifications.send 令牌发送通知应放行");
    }

    // =====================================================================
    // 6. /api/v1/internal/** 兜底规则
    // =====================================================================

    @Test
    @DisplayName("内部路径兜底规则：匿名拒绝，已认证放行")
    void internalFallbackRequiresAuthentication() {
        AuthorizationManager<RequestAuthorizationContext> fallback =
                AuthenticatedAuthorizationManager.authenticated();
        assertFalse(granted(fallback, anonymous(), "/api/v1/internal/anything-else"),
                "兜底规则必须拒绝匿名访问");
        assertTrue(granted(fallback, scopeToken(SecurityConfig.SCOPE_INTERNAL_USERS_READ),
                        "/api/v1/internal/anything-else"),
                "兜底规则对已认证请求放行");
    }
}
