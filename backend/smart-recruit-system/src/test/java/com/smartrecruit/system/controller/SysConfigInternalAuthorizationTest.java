package com.smartrecruit.system.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1-1 回归测试：{@code /api/v1/configs/internal/configs} 的权限契约。
 *
 * <p>背景：R3-B 之后 Recruitment 改用内部签名 JWT（仅授予 {@code SCOPE_configs.read}），
 * 而原注解 {@code hasRole('SYSTEM')} 只接受 {@code ROLE_SYSTEM}，导致配置读取必然被拒。</p>
 *
 * <p>本测试不启动 Spring 上下文、不连接数据库，仅做注解契约断言与授权判定。</p>
 */
@DisplayName("C1-1 System 内部配置接口权限契约")
class SysConfigInternalAuthorizationTest {

    private static final String SCOPE_CONFIGS_READ = "SCOPE_configs.read";
    private static final String ROLE_SYSTEM = "ROLE_SYSTEM";

    private static AuthorizationManager<RequestAuthorizationContext> manager() {
        return AuthorityAuthorizationManager.<RequestAuthorizationContext>hasAuthority(
                SCOPE_CONFIGS_READ);
    }

    private static boolean granted(Authentication authentication) {
        RequestAuthorizationContext context = new RequestAuthorizationContext(
                new MockHttpServletRequest("GET", "/api/v1/configs/internal/configs"));
        AuthorizationResult result = manager().authorize(() -> authentication, context);
        return result.isGranted();
    }

    private static Authentication scopeToken() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "smart-recruit-recruitment", null,
                List.of(new SimpleGrantedAuthority(SCOPE_CONFIGS_READ)));
    }

    private static Authentication roleSystem() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "system", null, List.of(new SimpleGrantedAuthority(ROLE_SYSTEM)));
    }

    private static Authentication normalUser() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "hr@company.com", 1001L,
                List.of(new SimpleGrantedAuthority("ROLE_HR"),
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("SCOPE_users.read")));
    }

    private static Authentication anonymous() {
        return new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    @Test
    @DisplayName("注解契约：只接受 SCOPE_configs.read（过渡 ROLE_SYSTEM 已随分支删除）")
    void annotationAcceptsScopeAndRoleSystem() throws Exception {
        Method method = SysConfigController.class.getMethod(
                "getInternalConfigs", String.class, String.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertNotEquals(null, preAuthorize, "该方法必须声明 @PreAuthorize");
        assertEquals("hasAuthority('SCOPE_configs.read')", preAuthorize.value(),
                "注解表达式必须与过滤器授予的 authority 一致");
    }

    @Test
    @DisplayName("Recruitment 的内部 scope 令牌可读取配置（回归已修复）")
    void scopeTokenAllowed() {
        assertTrue(granted(scopeToken()),
                "SCOPE_configs.read 必须放行，否则 AI 筛选配置读取被判 40102");
    }

    @Test
    @DisplayName("过渡期 ROLE_SYSTEM 已失效：不能读取内部配置")
    void roleSystemAllowed() {
        assertFalse(granted(roleSystem()),
                "ROLE_SYSTEM 不再由过滤器产生，注解也已移除该分支，必须拒绝");
    }

    @Test
    @DisplayName("普通用户令牌不能读取内部配置")
    void normalUserDenied() {
        assertFalse(granted(normalUser()),
                "普通用户不得读取内部配置项");
    }

    @Test
    @DisplayName("未认证请求不能读取内部配置")
    void anonymousDenied() {
        assertFalse(granted(anonymous()),
                "匿名请求不得读取内部配置项");
    }
}
