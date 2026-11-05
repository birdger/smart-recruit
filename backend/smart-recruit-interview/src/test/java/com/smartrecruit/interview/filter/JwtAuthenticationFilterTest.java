package com.smartrecruit.interview.filter;

import com.smartrecruit.common.security.InternalTokenService;
import com.smartrecruit.common.util.JwtUtil;
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
 * {@link JwtAuthenticationFilter}（Interview）单元测试。
 *
 * <p>覆盖 A3-1 验收要求：
 * <ul>
 *   <li>无凭证 / 伪造 {@code X-User-*} / 仅 {@code INNER-REQUEST:true} 均不能建立身份；</li>
 *   <li>签名错误、{@code aud} 不匹配、过期的令牌被拒绝；</li>
 *   <li>合法用户 JWT 建立正确身份与权限；</li>
 *   <li>合法内部 JWT 只获得声明的 {@code SCOPE_*}，且不会获得 {@code ROLE_SYSTEM}；</li>
 *   <li>用户 JWT 叠加伪造 {@code INNER-REQUEST} 不会升级权限。</li>
 * </ul>
 *
 * <p>说明：本测试直接断言真实 {@code SecurityContext}，未启动 Spring 容器；
 * HTTP 层（SecurityFilterChain 的 401/403 响应）属于集成测试，本轮未覆盖。
 */
@DisplayName("Interview JwtAuthenticationFilter 认证边界")
class JwtAuthenticationFilterTest {

    /** 测试专用用户 JWT 密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_USER_JWT_SECRET =
            "dGVzdC1qd3Qtc2VjcmV0LWZvci1zbWFydC1yZWNydWl0LWludGVydmlldy11bml0LXRlc3RzLTIwMjY=";
    /** 测试专用内部令牌密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_INTERNAL_SECRET =
            "dGVzdC1pbnRlcm5hbC1zZWNyZXQta2V5LWZvci1zbWFydC1yZWNydWl0LWludGVydmlldy11bml0LXRlc3Rz";
    /** 另一套内部密钥，用于构造签名错误的令牌。 */
    private static final String OTHER_INTERNAL_SECRET =
            "b3RoZXItaW50ZXJuYWwtc2VjcmV0LWtleS1mb3Itc21hcnQtcmVjcnVpdC1pbnRlcnZpZXctdGVzdHMtYmFk";

    private static final String INTERVIEW_SERVICE = "smart-recruit-interview";
    private static final String CALLER_SERVICE = "smart-recruit-talent";

    private JwtAuthenticationFilter filter;
    private InternalTokenService internalTokenService;
    private SecretKey userSecretKey;

    @BeforeEach
    void setUp() {
        internalTokenService = new InternalTokenService();
        ReflectionTestUtils.setField(internalTokenService, "secretKeyBase64", TEST_INTERNAL_SECRET);
        ReflectionTestUtils.setField(internalTokenService, "currentApplicationName", INTERVIEW_SERVICE);

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
        InternalTokenService foreignService = buildInternalService(OTHER_INTERNAL_SECRET, INTERVIEW_SERVICE);
        String token = foreignService.generateToken(
                CALLER_SERVICE, INTERVIEW_SERVICE, List.of("interviews.read"));

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "签名错误的内部令牌必须被拒绝");
    }

    @Test
    @DisplayName("内部令牌 audience 不匹配：拒绝")
    void internalTokenWithWrongAudience_shouldBeRejected() throws Exception {
        // 合法密钥，但 aud 是另一个服务
        InternalTokenService systemSideService = buildInternalService(TEST_INTERNAL_SECRET, "smart-recruit-system");
        String token = systemSideService.generateToken(
                CALLER_SERVICE, "smart-recruit-system", List.of("interviews.read"));

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "aud 与当前服务不符的内部令牌必须被拒绝");
    }

    @Test
    @DisplayName("用户 JWT 已过期：拒绝")
    void expiredUserJwt_shouldBeRejected() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "interview:view", -60L);

        doFilter(bearerRequest(token));

        assertNull(currentAuthentication(), "过期用户 JWT 必须被拒绝");
    }

    @Test
    @DisplayName("合法用户 JWT：建立用户身份与权限")
    void validUserJwt_shouldEstablishUserIdentity() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "interview:view,interview:manage");

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "合法用户 JWT 应建立认证");
        assertEquals("1001", authentication.getCredentials(), "credentials 必须是字符串 userId");
        assertEquals("hr01@example.com", authentication.getPrincipal());

        Set<String> authorities = authorityNames(authentication);
        assertTrue(authorities.contains("ROLE_HR"), "应包含角色权限");
        assertTrue(authorities.contains("interview:view"), "应包含权限码");
        assertFalse(authorities.stream().anyMatch(a -> a.startsWith("SCOPE_")), "用户令牌不应获得内部 scope");
        assertFalse(authorities.contains("ROLE_SYSTEM"), "用户令牌不应获得系统角色");
    }

    @Test
    @DisplayName("合法内部 JWT：只获得声明的 scope，且无 ROLE_SYSTEM")
    void validInternalToken_shouldGrantOnlyDeclaredScopes() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER_SERVICE, INTERVIEW_SERVICE, List.of("interviews.read"));

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "合法内部令牌应建立认证");
        assertEquals(CALLER_SERVICE, authentication.getPrincipal(), "principal 应为调用方服务名");
        assertNull(authentication.getCredentials(), "内部身份不应携带用户 ID");

        assertEquals(Set.of("SCOPE_interviews.read"), authorityNames(authentication),
                "内部令牌只能获得声明的 scope");
        assertFalse(authorityNames(authentication).contains("ROLE_SYSTEM"), "内部令牌不应获得 ROLE_SYSTEM");
    }

    @Test
    @DisplayName("合法用户 JWT 叠加伪造 INNER-REQUEST：不升级权限")
    void validUserJwtWithForgedInnerRequest_shouldNotEscalate() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 2002L, "hr02", "hr02@example.com",
                "HR", "interview:view");

        MockHttpServletRequest request = bearerRequest(token);
        request.addHeader("INNER-REQUEST", "true");
        request.addHeader("X-User-Role", "ADMIN");

        doFilter(request);

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication, "合法用户 JWT 应建立认证");
        Set<String> authorities = authorityNames(authentication);
        assertFalse(authorities.contains("ROLE_SYSTEM"), "伪造 INNER-REQUEST 不得授予系统角色");
        assertFalse(authorities.contains("ROLE_ADMIN"), "伪造 X-User-Role 不得授予角色");
        assertEquals("2002", authentication.getCredentials());
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
