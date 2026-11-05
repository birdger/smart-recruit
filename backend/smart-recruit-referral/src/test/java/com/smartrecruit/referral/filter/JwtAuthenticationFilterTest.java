package com.smartrecruit.referral.filter;

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

import javax.crypto.SecretKey;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JwtAuthenticationFilter}（Referral）单元测试。
 *
 * <p>复现并验证 A3-1 修复的身份头信任风险：修改前过滤器优先采信网关注入的
 * {@code X-User-Id / X-User-Role}，直连端口伪造这些请求头即可建立任意身份，
 * 直接绕过 {@code @EnableMethodSecurity} 下 {@code isAuthenticated()} 的方法级校验。
 * 现在身份只能来自验签成功的用户 JWT。
 *
 * <p>同时验证 {@code @PreAuthorize} 依赖的 authorities 语义保持不变：
 * 角色映射为 {@code ROLE_*}，权限码声明映射为原样权限。
 */
@DisplayName("Referral JwtAuthenticationFilter 身份头信任修复")
class JwtAuthenticationFilterTest {

    /** 测试专用用户 JWT 密钥（Base64，与生产配置隔离）。 */
    private static final String TEST_USER_JWT_SECRET =
            "dGVzdC1qd3Qtc2VjcmV0LWZvci1zbWFydC1yZWNydWl0LXJlZmVycmFsLXVuaXQtdGVzdHMtMjAyNg==";
    /** 另一套用户密钥，用于构造签名错误的令牌。 */
    private static final String OTHER_USER_JWT_SECRET =
            "b3RoZXItand0LXNlY3JldC1rZXktZm9yLXNtYXJ0LXJlY3J1aXQtcmVmZXJyYWwtdGVzdHMtYmFkLXNpZw==";

    private JwtAuthenticationFilter filter;
    private SecretKey userSecretKey;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(TEST_USER_JWT_SECRET);
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

        assertNull(currentAuthentication(),
                "伪造身份头不能建立认证（修复前会建立 ROLE_ADMIN 身份并通过 isAuthenticated() 校验）");
    }

    @Test
    @DisplayName("合法用户 JWT：建立身份、角色与权限码")
    void validUserJwt_shouldEstablishUserIdentity() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "referral:view,referral:audit");

        doFilter(bearerRequest(token));

        Authentication authentication = currentAuthentication();
        assertNotNull(authentication);
        assertEquals("1001", authentication.getCredentials(), "credentials 保持 userId 语义");
        assertEquals("hr01", authentication.getPrincipal(), "principal 保持用户名为语义");

        Set<String> authorities = authorityNames(authentication);
        assertTrue(authorities.contains("ROLE_HR"), "角色映射不得变化");
        assertTrue(authorities.contains("referral:view"), "权限码映射不得变化");
    }

    @Test
    @DisplayName("过期 / 签名错误的用户 JWT：拒绝且不回退匿名或高权限")
    void invalidUserJwt_shouldBeRejected() throws Exception {
        String expired = JwtUtil.createAccessToken(userSecretKey, 1001L, "hr01", "hr01@example.com",
                "HR", "referral:view", -60L);
        doFilter(bearerRequest(expired));
        assertNull(currentAuthentication(), "过期令牌必须被拒绝");

        SecretKey otherKey = JwtUtil.getSecretKey(OTHER_USER_JWT_SECRET);
        String wrongSignature = JwtUtil.createAccessToken(otherKey, 1001L, "hr01", "hr01@example.com",
                "HR", "referral:view");
        doFilter(bearerRequest(wrongSignature));
        assertNull(currentAuthentication(), "签名错误令牌必须被拒绝");
    }

    @Test
    @DisplayName("伪造身份头 + 非法令牌：仍不建立身份")
    void forgedHeadersWithInvalidToken_shouldNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "999");
        request.addHeader("X-User-Role", "ADMIN");
        request.addHeader("Authorization", "Bearer not-a-jwt");

        doFilter(request);

        assertNull(currentAuthentication(), "非法令牌不得借助请求头建立身份");
    }

    @Test
    @DisplayName("Cookie 中的合法用户 JWT：仍可建立身份（公开端点带登录态场景）")
    void userJwtInCookie_shouldAuthenticate() throws Exception {
        String token = JwtUtil.createAccessToken(userSecretKey, 2002L, "candidate", "candidate@example.com",
                "CANDIDATE", "referral:submit");

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
