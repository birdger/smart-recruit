package com.smartrecruit.system.filter;

import com.smartrecruit.common.security.InternalTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JwtAuthenticationFilter} 三分支认证流程闭环测试。
 *
 * <p>测试使用**真实** {@link InternalTokenService} 实例（非 mock），
 * 通过反射注入 Base64 测试密钥和 application.name，
 * 验证过滤器对真实内部 JWT 签发/验证的完整交互路径。
 *
 * <h3>测试覆盖场景</h3>
 * <ol>
 *   <li>✅ 内部令牌验证成功 + scope 满足 → 建立内部服务身份 (SCOPE_users.read)</li>
 *   <li>✅ scope 不足（缺必需权限）→ 过滤器不设 Authentication，SecurityContext 空</li>
 *   <li>✅ Authorization 头存在 + aud 不匹配 + INNER-REQUEST=true 同时存在 →
 *       SecurityContext 空（**安全边界：Authorization 头存在时永不 fallback INNER-REQUEST**）</li>
 *   <li>✅ Authorization 头不存在 + INNER-REQUEST=true → SecurityContext 空（R1-A 已删除过渡分支）</li>
 *   <li>✅ Authorization 头存在 + 合法 scope + INNER-REQUEST=true 同时存在 →
 *       只授予 scope authorities，**同时存在的 INNER-REQUEST 不会额外提升 ROLE_SYSTEM**</li>
 * </ol>
 */
@DisplayName("System JwtAuthenticationFilter — 内部服务 JWT + 过渡期 INNER-REQUEST 双路闭环")
class JwtAuthenticationFilterInternalTokenTest {

    /** 测试专用 Base64 密钥。与生产完全隔离。 */
    private static final String TEST_SECRET_BASE64 =
            "dGVzdC1pbnRlcm5hbC1zZWNyZXQta2V5LWZvci1zeXN0ZW0tZmlsdGVyLXRlc3Q=";
    /** 不同密钥，用于签 "aud 不匹配" 场景的 token。 */
    private static final String OTHER_SECRET_BASE64 =
            "b3RoZXItc2VjcmV0LWtleS1mb3ItdGVzdGluZy1hdWQtd3Jvbmctc2NlbmFyaW8=";

    private static final String CALLER = "smart-recruit-recruitment";
    private static final String SYSTEM_NAME = "smart-recruit-system";

    private JwtAuthenticationFilter filter;
    private InternalTokenService internalTokenService;
    private InternalTokenService otherSecretTokenService;

    // 过滤器链执行后捕获实际 SecurityContext 状态
    private UsernamePasswordAuthenticationToken capturedAuth;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        capturedAuth = null;

        // 构造真实 InternalTokenService（验证 smart-recruit-system 自己能验）
        internalTokenService = new InternalTokenService();
        inject(internalTokenService, "secretKeyBase64", TEST_SECRET_BASE64);
        inject(internalTokenService, "currentApplicationName", SYSTEM_NAME);

        // 另一个密钥的 InternalTokenService（签发 aud 正确但签名不同的 token）
        otherSecretTokenService = new InternalTokenService();
        inject(otherSecretTokenService, "secretKeyBase64", OTHER_SECRET_BASE64);
        inject(otherSecretTokenService, "currentApplicationName", SYSTEM_NAME);

        // 过滤器注入：internalTokenService（验内部 JWT）+ jwtSecret（用户 JWT 密钥，随便给）
        filter = new JwtAuthenticationFilter(internalTokenService);
        ReflectionTestUtils.setField(filter, "jwtSecret",
                "dGVzdC11c2VyLWp3dC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbm90LXByb2Q=");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void inject(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }

    // =====================================================================
    // Helper: 执行 filter，捕获最终 SecurityContext 中的 Authentication
    // =====================================================================

    private void doFilter(HttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
                capturedAuth = (UsernamePasswordAuthenticationToken)
                        SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(request, response, chain);
    }

    private MockHttpServletRequest newRequest(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setMethod("GET");
        r.setRequestURI(path);
        r.setServletPath(path);
        return r;
    }

    // =====================================================================
    // 1. 内部令牌 + scope 满足 → 建立 SCOPE_users.read
    // =====================================================================

    @Test
    @DisplayName("内部令牌 + scope 满足 → 建立 SCOPE_users.read authority")
    void internalToken_ok_scopeSatisfied() throws Exception {
        // path=/api/v1/users/123 → SCOPE_PER_PATH_PREFIX 最长前缀 "/api/v1/users"
        //   需要完整 scope 列表：users.read users.create users.update users.delete
        // generateToken 把全部必需 scope 都放进 claims.scope
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME,
                List.of("users.read", "users.create", "users.update", "users.delete"));

        MockHttpServletRequest request = newRequest("/api/v1/users/123");
        request.addHeader("Authorization", "Bearer " + token);

        doFilter(request);

        assertNotNull(capturedAuth, "内部令牌验证成功后应建立 Authentication");
        assertEquals(CALLER, capturedAuth.getPrincipal());
        List<String> names = authorityNames(capturedAuth);
        assertTrue(names.contains("SCOPE_users.read"),
                "应授予 SCOPE_users.read，实际: " + names);
    }

    // =====================================================================
    // 2. scope 不足 → SecurityContext 空
    // =====================================================================

    @Test
    @DisplayName("scope 不足（缺必需权限）→ SecurityContext 空")
    void internalToken_scopeMissing() throws Exception {
        // 只签三个（缺 users.read）去读 /api/v1/users/{id}（需要完整四个）
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME,
                List.of("users.create", "users.update", "users.delete"));

        MockHttpServletRequest request = newRequest("/api/v1/users/123");
        request.addHeader("Authorization", "Bearer " + token);

        doFilter(request);

        assertNull(capturedAuth,
                "scope 不足时过滤器应返回 null，SecurityContext 保持空，"
                        + "让 SecurityFilterChain 的 authenticated() 拦截（最终 401）");
    }

    // =====================================================================
    // 3. 安全边界：Authorization 头存在时 INNER-REQUEST 不生效
    // =====================================================================

    @Test
    @DisplayName("安全边界：Authorization 头存在 + INNER-REQUEST=true + 内部令牌签名错 → SecurityContext 空")
    void securityBoundary_authHeaderPresent_innerRequestIgnored() throws Exception {
        // 用另一密钥签（对 System 来说签名错），aud 对
        String badToken = otherSecretTokenService.generateToken(
                CALLER, SYSTEM_NAME, List.of("users.read"));

        MockHttpServletRequest request = newRequest("/api/v1/users/456");
        request.addHeader("Authorization", "Bearer " + badToken);
        request.addHeader("INNER-REQUEST", "true"); // 攻击者同时伪造

        doFilter(request);

        assertNull(capturedAuth,
                "安全边界：Authorization 头存在时，"
                        + "即使 INNER-REQUEST=true + 内部令牌签名错，也**永不** fallback 到 ROLE_SYSTEM。"
                        + "否则攻击者伪造 Authorization（签名错）会被 INNER-REQUEST 抢救成 ROLE_SYSTEM。");
    }

    // =====================================================================
    // 4. Authorization 头不存在 + INNER-REQUEST=true → ROLE_SYSTEM（过渡期）
    // =====================================================================

    @Test
    @DisplayName("Authorization 头不存在 + INNER-REQUEST=true → 不建立身份（R1-A 已删除过渡分支）")
    void innerRequest_only_grantsSystem() throws Exception {
        MockHttpServletRequest request = newRequest("/api/v1/users/789");
        request.addHeader("INNER-REQUEST", "true");
        // 无 Authorization 头

        doFilter(request);

        assertNull(capturedAuth,
                "INNER-REQUEST 不再是合法身份来源，SecurityContext 必须为空");
    }

    // =====================================================================
    // 5. Authorization 头存在 + 内部令牌通过 + INNER-REQUEST=true → 只授予 scope
    //    双路并存期间，合法内部令牌不应被 INNER-REQUEST 额外叠加 ROLE_SYSTEM
    // =====================================================================

    @Test
    @DisplayName("双路并存：合法内部令牌 + INNER-REQUEST=true → 只授予 scope authorities，不叠加 ROLE_SYSTEM")
    void bothHeaders_validInternalToken_noRoleSystem() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER, SYSTEM_NAME,
                List.of("users.read", "users.create", "users.update", "users.delete"));

        MockHttpServletRequest request = newRequest("/api/v1/users/321");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("INNER-REQUEST", "true");

        doFilter(request);

        assertNotNull(capturedAuth);
        List<String> names = authorityNames(capturedAuth);
        // ✅ 内部令牌生效
        assertTrue(names.contains("SCOPE_users.read"),
                "内部令牌生效时应有 SCOPE_users.read");
        // ❌ INNER-REQUEST 不会额外叠加
        assertFalse(names.contains("ROLE_SYSTEM"),
                "Authorization 头存在时 INNER-REQUEST 分支不触发，不应叠加 ROLE_SYSTEM。"
                        + "实际 authorities: " + names);
    }

    // =====================================================================
    // helper
    // =====================================================================

    private List<String> authorityNames(UsernamePasswordAuthenticationToken auth) {
        List<String> names = new ArrayList<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            names.add(ga.getAuthority());
        }
        return names;
    }
}
