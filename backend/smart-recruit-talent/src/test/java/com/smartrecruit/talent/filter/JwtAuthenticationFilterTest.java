package com.smartrecruit.talent.filter;

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
 * {@link JwtAuthenticationFilter}（Talent）单元测试。
 *
 * <p>复现并验证 A3-1 修复的身份头信任风险：修改前过滤器直接采信网关注入的
 * {@code X-User-Id / X-Username / X-User-Role}，直连端口伪造这些请求头即可建立任意身份。
 * 现在身份只能来自验签成功的用户 JWT 或服务间内部 JWT。
 */
@DisplayName("Talent JwtAuthenticationFilter 身份头信任修复")
class JwtAuthenticationFilterTest {

    /** 测试专用用户 JWT 密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_USER_JWT_SECRET =
            "dGVzdC1qd3Qtc2VjcmV0LWZvci1zbWFydC1yZWNydWl0LXRhbGVudC11bml0LXRlc3RzLTIwMjYtMDY=";
    /** 测试专用内部令牌密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_INTERNAL_SECRET =
            "dGVzdC1pbnRlcm5hbC1zZWNyZXQta2V5LWZvci1zbWFydC1yZWNydWl0LXRhbGVudC11bml0LXRlc3Rz";
    /** 另一套用户密钥，用于构造签名错误的令牌。 */
    private static final String OTHER_USER_JWT_SECRET =
            "b3RoZXItand0LXNlY3JldC1rZXktZm9yLXNtYXJ0LXJlY3J1aXQtdGFsZW50LXRlc3RzLWJhZC1zaWc=";

    private static final String TALENT_SERVICE = "smart-recruit-talent";
    private static final String CALLER_SERVICE = "smart-recruit-interview";

    private JwtAuthenticationFilter filter;
    private InternalTokenService internalTokenService;
    private SecretKey userSecretKey;

    @BeforeEach
    void setUp() {
        internalTokenService = new InternalTokenService();
        ReflectionTestUtils.setField(internalTokenService, "secretKeyBase64", TEST_INTERNAL_SECRET);
        ReflectionTestUtils.setField(internalTokenService, "currentApplicationName", TALENT_SERVICE);

        filter = new JwtAuthenticationFilter(internalTokenService, TEST_USER_JWT_SECRET);
        userSecretKey = JwtUtil.getSecretKey(TEST_USER_JWT_SECRET);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("风险复现：仅伪造 X-User-* 请求头不再建立身份")
    void forgedIdentityHeaders_shouldNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-Username", "attacker");
        request.addHeader("X-User-Role", "ADMIN");

        doFilter(request);

        assertNull(currentAuthentication(), "伪造身份头不能建立认证（修复前会建立 ROLE_ADMIN 身份）");
    }

    @Test
    @DisplayName("仅 INNER-REQUEST:true：不建立内部身份")
    void innerRequestHeaderOnly_shouldNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("INNER-REQUEST", "true");

        doFilter(request);

        assertNull(currentAuthentication());
    }

    @Test
    @DisplayName("合法用户 JWT：建立用户身份，credentials 为 userId")
    void validUserJwt_shouldEstablishUserIdentity() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "talent:view,talent:edit");

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
        assertEquals("1001", authentication.getCredentials(), "getCurrentUserId() 依赖 credentials");
        assertEquals("hr01", authentication.getPrincipal(), "principal 保持用户名为语义");

        Set<String> authorities = authorityNames(authentication);
        assertTrue(authorities.contains("ROLE_HR"));
        assertTrue(authorities.contains("talent:view"));
    }

    @Test
    @DisplayName("签名错误 / 过期的用户 JWT：拒绝")
    void invalidUserJwt_shouldBeRejected() throws Exception {
        String expired = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "talent:view", -60L);
        doFilter(bearerRequest(expired));
        assertNull(currentAuthentication(), "过期令牌必须被拒绝");

        SecretKey otherKey = JwtUtil.getSecretKey(OTHER_USER_JWT_SECRET);
        String wrongSignature = JwtUtil.createAccessToken(otherKey, 1001L, "hr01", "hr01@example.com",
                "HR", "talent:view");
        doFilter(bearerRequest(wrongSignature));
        assertNull(currentAuthentication(), "签名错误令牌必须被拒绝");
    }

    @Test
    @DisplayName("合法内部 JWT：只获得声明 scope，无用户 ID")
    void validInternalToken_shouldGrantScopesOnly() throws Exception {
        String token = internalTokenService.generateToken(
                CALLER_SERVICE, TALENT_SERVICE, List.of("talent.candidates.write"));

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
        assertEquals(CALLER_SERVICE, authentication.getPrincipal());
        assertNull(authentication.getCredentials(), "内部身份不得伪装成普通用户 ID");
        assertEquals(Set.of("SCOPE_talent.candidates.write"), authorityNames(authentication));
    }

    @Test
    @DisplayName("Cookie 中的合法用户 JWT：仍可建立身份（兼容网关 Cookie 令牌）")
    void userJwtInCookie_shouldAuthenticate() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 2002L, "hr02", "hr02@example.com",
                "HR", "talent:view");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("access_token", token));

        doFilter(request);

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
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

    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Set<String> authorityNames(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
