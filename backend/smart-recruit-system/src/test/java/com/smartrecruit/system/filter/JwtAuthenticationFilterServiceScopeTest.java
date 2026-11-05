package com.smartrecruit.system.filter;

import com.smartrecruit.common.security.InternalTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 补充测试：文件/内部接口路径上的 scope 与过渡期身份的过滤器级行为。
 *
 * <p>与 {@code SecurityConfigServiceEndpointRuleTest} 互补：本测试验证过滤器
 * **产出的 authority**，规则测试验证这些 authority 能否通过授权判定。</p>
 *
 * <p>不启动 Spring 上下文、不连接数据库、不触发文件读写。</p>
 */
@DisplayName("System JwtAuthenticationFilter — 服务间接口 scope 行为")
class JwtAuthenticationFilterServiceScopeTest {

    private static final String TEST_SECRET_BASE64 =
            "dGVzdC1pbnRlcm5hbC1zZWNyZXQta2V5LWZvci1zeXN0ZW0tZmlsdGVyLXRlc3Q=";
    private static final String CALLER = "smart-recruit-recruitment";
    private static final String SYSTEM_NAME = "smart-recruit-system";

    private JwtAuthenticationFilter filter;
    private InternalTokenService internalTokenService;
    private UsernamePasswordAuthenticationToken capturedAuth;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        capturedAuth = null;

        internalTokenService = new InternalTokenService();
        ReflectionTestUtils.setField(internalTokenService, "secretKeyBase64", TEST_SECRET_BASE64);
        ReflectionTestUtils.setField(internalTokenService, "currentApplicationName", SYSTEM_NAME);

        filter = new JwtAuthenticationFilter(internalTokenService);
        ReflectionTestUtils.setField(filter, "jwtSecret",
                "dGVzdC11c2VyLWp3dC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void doFilter(HttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
                capturedAuth = (UsernamePasswordAuthenticationToken)
                        SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(request, response, chain);
    }

    private static MockHttpServletRequest newRequest(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setMethod(method);
        r.setRequestURI(path);
        r.setServletPath(path);
        return r;
    }

    private static List<String> authorityNames(UsernamePasswordAuthenticationToken auth) {
        List<String> names = new ArrayList<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            names.add(a.getAuthority());
        }
        return names;
    }

    @Test
    @DisplayName("files.delete 内部令牌 → 建立 SCOPE_files.delete，可删除")
    void deleteTokenYieldsDeleteScope() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME, List.of("files.delete"));

        MockHttpServletRequest request = newRequest("DELETE", "/api/v1/files/delete");
        request.addHeader("Authorization", "Bearer " + token);
        doFilter(request);

        assertNotNull(capturedAuth, "合法内部令牌应建立 Authentication");
        assertEquals(CALLER, capturedAuth.getPrincipal());
        assertTrue(authorityNames(capturedAuth).contains("SCOPE_files.delete"),
                "应授予 SCOPE_files.delete，实际: " + authorityNames(capturedAuth));
    }

    @Test
    @DisplayName("files.upload 内部令牌访问删除路径 → scope 不足，不建立身份")
    void uploadTokenCannotDelete() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME, List.of("files.upload"));

        MockHttpServletRequest request = newRequest("DELETE", "/api/v1/files/delete");
        request.addHeader("Authorization", "Bearer " + token);
        doFilter(request);

        assertNull(capturedAuth,
                "scope 不足时必须不建立身份，由 SecurityFilterChain 拒绝");
    }

    @Test
    @DisplayName("无任何认证信息访问删除路径 → 不建立身份（匿名不会被动授予 ROLE_SYSTEM）")
    void anonymousDeleteHasNoIdentity() throws Exception {
        doFilter(newRequest("DELETE", "/api/v1/files/delete"));
        assertNull(capturedAuth, "匿名请求不得产生任何 Authentication");
    }

    @Test
    @DisplayName("Authorization 存在但 scope 不足 + INNER-REQUEST=true → 不 fallback 到 ROLE_SYSTEM")
    void insufficientScopeDoesNotFallbackToInnerRequest() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME, List.of("files.download"));

        MockHttpServletRequest request = newRequest("DELETE", "/api/v1/files/delete");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("INNER-REQUEST", "true");
        doFilter(request);

        assertNull(capturedAuth,
                "Authorization 头存在时永不回退 INNER-REQUEST，否则构成提权");
    }

    @Test
    @DisplayName("R1-A 后：仅 INNER-REQUEST（无 Authorization）→ 不建立身份")
    void innerRequestOnlyGrantsNothing() throws Exception {
        MockHttpServletRequest request = newRequest("GET", "/api/v1/internal/users/admins");
        request.addHeader("INNER-REQUEST", "true");
        doFilter(request);

        assertNull(capturedAuth,
                "过渡分支删除后，伪造 INNER-REQUEST 不得建立任何身份");
    }

    // =====================================================================
    // 阶段二：用户管理按 HTTP 方法细分 scope（最小权限）
    // =====================================================================

    @Test
    @DisplayName("GET /api/v1/users/{id} 仅需 users.read（不再强制写权限）")
    void getUsersRequiresReadOnly() throws Exception {
        String token = internalTokenService.generateToken(
                "smart-recruit-offer", SYSTEM_NAME, List.of("users.read"));

        MockHttpServletRequest request = newRequest("GET", "/api/v1/users/1001");
        request.addHeader("Authorization", "Bearer " + token);
        doFilter(request);

        assertNotNull(capturedAuth, "users.read 令牌访问用户详情应通过");
        assertTrue(authorityNames(capturedAuth).contains("SCOPE_users.read"));
    }

    @Test
    @DisplayName("POST /api/v1/users 需要 users.create，仅有 users.read 时拒绝")
    void createUserRequiresCreateScope() throws Exception {
        String readOnly = internalTokenService.generateToken(
                "smart-recruit-offer", SYSTEM_NAME, List.of("users.read"));

        MockHttpServletRequest denied = newRequest("POST", "/api/v1/users");
        denied.addHeader("Authorization", "Bearer " + readOnly);
        doFilter(denied);
        assertNull(capturedAuth, "users.read 不得用于创建用户");

        String createToken = internalTokenService.generateToken(
                "smart-recruit-offer", SYSTEM_NAME, List.of("users.create"));
        MockHttpServletRequest allowed = newRequest("POST", "/api/v1/users");
        allowed.addHeader("Authorization", "Bearer " + createToken);
        doFilter(allowed);
        assertNotNull(capturedAuth, "users.create 令牌创建用户应通过");
        assertTrue(authorityNames(capturedAuth).contains("SCOPE_users.create"));
    }

    @Test
    @DisplayName("GET /api/v1/configs/internal/configs：configs.read 令牌可访问（C1-1 链路）")
    void configsReadScopeAccepted() throws Exception {
        String token = internalTokenService.generateToken(
                "smart-recruit-recruitment", SYSTEM_NAME, List.of("configs.read"));

        MockHttpServletRequest request = newRequest("GET", "/api/v1/configs/internal/configs");
        request.addHeader("Authorization", "Bearer " + token);
        doFilter(request);

        assertNotNull(capturedAuth, "configs.read 令牌读取内部配置应在过滤器层通过");
        assertTrue(authorityNames(capturedAuth).contains("SCOPE_configs.read"));
    }

    @Test
    @DisplayName("download-bytes 命中 files.download 前缀")
    void downloadBytesMatchesDownloadScope() throws Exception {
        String token = internalTokenService.generateToken(
                "smart-recruit-referral", SYSTEM_NAME, List.of("files.download"));

        MockHttpServletRequest request = newRequest("GET", "/api/v1/files/download-bytes");
        request.addHeader("Authorization", "Bearer " + token);
        doFilter(request);

        assertNotNull(capturedAuth, "files.download 令牌访问 download-bytes 应通过");
    }
}
