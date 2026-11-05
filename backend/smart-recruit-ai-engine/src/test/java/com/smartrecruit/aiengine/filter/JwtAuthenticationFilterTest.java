package com.smartrecruit.aiengine.filter;

import com.smartrecruit.common.security.InternalTokenService;
import com.smartrecruit.common.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JwtAuthenticationFilter}（AI-Engine）单元测试。
 *
 * <p>覆盖 A3-1 验收要求：
 * <ul>
 *   <li>无凭证 / 伪造 {@code X-User-*} / 仅 {@code INNER-REQUEST:true} 均不能建立身份；</li>
 *   <li>签名错误、{@code aud} 不匹配、过期的令牌被拒绝；</li>
 *   <li>合法用户 JWT 只获得用户权限，<b>不会</b>获得内部 {@code SCOPE_*}（内部能力端点因此拒绝）；</li>
 *   <li>合法内部 JWT 只获得其声明的 scope，未声明的能力 scope 无法获得；</li>
 *   <li>Cookie / 查询参数携带令牌的既有前端方式仍然可用。</li>
 * </ul>
 *
 * <p>说明：本测试直接断言真实 {@code SecurityContext}，未启动 Spring 容器；
 * SecurityFilterChain 的 401/403 响应属于集成测试，本轮未覆盖。
 */
@DisplayName("AI-Engine JwtAuthenticationFilter 认证边界")
class JwtAuthenticationFilterTest {

    /** 测试专用用户 JWT 密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_USER_JWT_SECRET =
            "dGVzdC1qd3Qtc2VjcmV0LWZvci1zbWFydC1yZWNydWl0LWFpLWVuZ2luZS11bml0LXRlc3RzLTIwMjY=";
    /** 测试专用内部令牌密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_INTERNAL_SECRET =
            "dGVzdC1pbnRlcm5hbC1zZWNyZXQta2V5LWZvci1zbWFydC1yZWNydWl0LWFpLWVuZ2luZS11bml0LXRlc3Rz";
    /** 另一套内部密钥，用于构造签名错误的令牌。 */
    private static final String OTHER_INTERNAL_SECRET =
            "b3RoZXItaW50ZXJuYWwtc2VjcmV0LWtleS1mb3Itc21hcnQtcmVjcnVpdC1haS1lbmdpbmUtdGVzdHMtYmFk";

    private static final String AI_ENGINE_SERVICE = "smart-recruit-ai-engine";
    private static final String CALLER_SERVICE = "smart-recruit-recruitment";
    private static final String CAPABILITY_SCOPE = "ai.capabilities.invoke";

    private JwtAuthenticationFilter filter;
    private InternalTokenService internalTokenService;
    private SecretKey userSecretKey;

    @BeforeEach
    void setUp() {
        internalTokenService = new InternalTokenService();
        ReflectionTestUtils.setField(internalTokenService, "secretKeyBase64", TEST_INTERNAL_SECRET);
        ReflectionTestUtils.setField(internalTokenService, "currentApplicationName", AI_ENGINE_SERVICE);

        filter = new JwtAuthenticationFilter(internalTokenService, TEST_USER_JWT_SECRET);
        userSecretKey = JwtUtil.getSecretKey(TEST_USER_JWT_SECRET);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("无任何凭证：不建立身份")
    void noCredential_shouldNotAuthenticate() throws Exception {
        doFilter(new MockHttpServletRequest());

        assertNull(currentAuthentication(), "无令牌时不应建立认证");
    }

    @Test
    @DisplayName("仅伪造 X-User-Id / X-Username / X-User-Role：不建立身份")
    void forgedIdentityHeaders_shouldNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-Username", "attacker");
        request.addHeader("X-User-Role", "ADMIN");

        doFilter(request);

        assertNull(currentAuthentication(), "伪造身份头不能建立认证");
    }

    @Test
    @DisplayName("仅 INNER-REQUEST:true：不能建立内部身份")
    void innerRequestHeaderOnly_shouldNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("INNER-REQUEST", "true");

        doFilter(request);

        assertNull(currentAuthentication(), "INNER-REQUEST 请求头不能建立内部身份");
    }

    @Test
    @DisplayName("内部令牌签名错误：拒绝")
    void internalTokenWithWrongSignature_shouldBeRejected() throws Exception {
        InternalTokenService foreignService = buildInternalService(OTHER_INTERNAL_SECRET, AI_ENGINE_SERVICE);
        String token = foreignService.generateToken(
                CALLER_SERVICE, AI_ENGINE_SERVICE, List.of(CAPABILITY_SCOPE));

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "签名错误的内部令牌必须被拒绝");
    }

    @Test
    @DisplayName("内部令牌 audience 不匹配：拒绝")
    void internalTokenWithWrongAudience_shouldBeRejected() throws Exception {
        InternalTokenService interviewSideService = buildInternalService(TEST_INTERNAL_SECRET, "smart-recruit-interview");
        String token = interviewSideService.generateToken(
                CALLER_SERVICE, "smart-recruit-interview", List.of(CAPABILITY_SCOPE));

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "aud 与当前服务不符的内部令牌必须被拒绝");
    }

    @Test
    @DisplayName("用户 JWT 已过期：拒绝")
    void expiredUserJwt_shouldBeRejected() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "admin", "admin@example.com",
                "ADMIN", "agent:view", -60L);

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "过期用户 JWT 必须被拒绝");
    }

    @Test
    @DisplayName("合法用户 JWT：建立用户身份，但不含内部 SCOPE（内部能力端点拒绝）")
    void validUserJwt_shouldNotObtainInternalScope() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "admin", "admin@example.com",
                "ADMIN", "agent:view,agent:manage");

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "合法用户 JWT 应建立认证");
        assertEquals("1001", authentication.getCredentials(), "credentials 必须是字符串 userId");

        Set<String> authorities = authorityNames(authentication);
        assertTrue(authorities.contains("ROLE_ADMIN"));
        assertTrue(authorities.contains("agent:view"));
        assertFalse(authorities.contains("SCOPE_" + CAPABILITY_SCOPE),
                "用户 JWT 不得获得内部能力 scope（/api/v1/agent-capabilities/** 应拒绝）");
        assertFalse(authorities.contains("ROLE_SYSTEM"));
    }

    @Test
    @DisplayName("合法内部 JWT：获得声明的能力 scope")
    void validInternalToken_shouldGrantDeclaredScopeOnly() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER_SERVICE, AI_ENGINE_SERVICE, List.of(CAPABILITY_SCOPE));

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "合法内部令牌应建立认证");
        assertEquals(CALLER_SERVICE, authentication.getPrincipal(), "principal 应为调用方服务名");
        assertNull(authentication.getCredentials(), "内部身份不应携带用户 ID");
        assertEquals(Set.of("SCOPE_" + CAPABILITY_SCOPE), authorityNames(authentication));
    }

    @Test
    @DisplayName("内部 JWT 未声明能力 scope：无法获得该 scope")
    void internalTokenWithoutCapabilityScope_shouldNotGainCapability() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER_SERVICE, AI_ENGINE_SERVICE, List.of("ai.llm.chat"));

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
        Set<String> authorities = authorityNames(authentication);
        assertTrue(authorities.contains("SCOPE_ai.llm.chat"));
        assertFalse(authorities.contains("SCOPE_" + CAPABILITY_SCOPE),
                "内部令牌只能获得声明的 scope，能力端点应因缺少 scope 被拒绝");
    }

    @Test
    @DisplayName("Cookie 中的合法用户 JWT：仍可建立身份（兼容网关注入方式）")
    void userJwtInCookie_shouldAuthenticate() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 3003L, "admin", "admin@example.com",
                "ADMIN", "agent:view");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", token));

        doFilter(request);

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "Cookie 令牌应建立认证");
        assertEquals("3003", authentication.getCredentials());
    }

    @Test
    @DisplayName("查询参数中的合法用户 JWT：仍可建立身份（兼容 SSE）")
    void userJwtInQueryParam_shouldAuthenticate() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 4004L, "admin", "admin@example.com",
                "ADMIN", "agent:view");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", token);

        doFilter(request);

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "查询参数令牌应建立认证");
        assertEquals("4004", authentication.getCredentials());
    }

    // ================= 辅助方法 =================

    private void doFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private MockHttpServletRequest bearerRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private InternalTokenService buildInternalService(String secret, String applicationName) {
        InternalTokenService service = new InternalTokenService();
        ReflectionTestUtils.setField(service, "secretKeyBase64", secret);
        ReflectionTestUtils.setField(service, "currentApplicationName", applicationName);
        return service;
    }

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Set<String> authorityNames(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
