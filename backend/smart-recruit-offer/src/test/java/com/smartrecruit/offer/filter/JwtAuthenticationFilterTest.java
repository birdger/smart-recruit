package com.smartrecruit.offer.filter;

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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offer 模块 {@link JwtAuthenticationFilter} 的认证语义测试。
 *
 * <p>覆盖三类关键行为：
 * <ol>
 *   <li>R1-A：{@code INNER-REQUEST: true} 头不再授予 {@code ROLE_SYSTEM}，也不建立任何身份；</li>
 *   <li>服务间内部令牌（aud=smart-recruit-offer）通过校验后只授予 {@code SCOPE_*}；</li>
 *   <li>用户 JWT 仍按原有角色/权限码语义工作。</li>
 * </ol>
 */
class JwtAuthenticationFilterTest {

    /** 内部令牌测试密钥（仅测试用，非生产配置）。 */
    private static final String INTERNAL_SECRET_BASE64 = Base64.getEncoder()
            .encodeToString("offer-filter-test-internal-key-32bytes!!".getBytes(StandardCharsets.UTF_8));

    /** 用户 JWT 测试密钥（仅测试用，非生产配置）。 */
    private static final String USER_JWT_SECRET_BASE64 = Base64.getEncoder()
            .encodeToString("offer-filter-test-user-jwt-key-32bytes!!".getBytes(StandardCharsets.UTF_8));

    private InternalTokenService internalTokenService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        internalTokenService = new InternalTokenService();
        ReflectionTestUtils.setField(internalTokenService, "secretKeyBase64", INTERNAL_SECRET_BASE64);
        ReflectionTestUtils.setField(internalTokenService, "currentApplicationName", "smart-recruit-offer");
        filter = new JwtAuthenticationFilter(internalTokenService, USER_JWT_SECRET_BASE64);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void doFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    @DisplayName("仅携带 INNER-REQUEST 头：不再授予 ROLE_SYSTEM，也不建立身份")
    void innerRequestHeaderAloneLeavesNoAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/analytics/offer-stats");
        request.addHeader("INNER-REQUEST", "true");

        doFilter(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "INNER-REQUEST 头不得再建立任何认证身份");
    }

    @Test
    @DisplayName("合法内部令牌：按调用方身份认证，仅授予 SCOPE_*")
    void validInternalTokenAuthenticatesWithScopes() throws Exception {
        String token = internalTokenService.generateToken(
                "smart-recruit-talent", "smart-recruit-offer", List.of("offers.read"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/analytics/offer-stats");
        request.addHeader("Authorization", "Bearer " + token);

        doFilter(request);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "合法内部令牌应建立认证身份");
        assertEquals("smart-recruit-talent", authentication.getName());
        assertNull(authentication.getCredentials(), "内部令牌不携带用户凭证");
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertTrue(authorities.contains("SCOPE_offers.read"), "scope 应映射为 SCOPE_ 权限");
        assertTrue(!authorities.contains("ROLE_SYSTEM"), "内部令牌不得授予 ROLE_SYSTEM");
    }

    @Test
    @DisplayName("aud 非本服务的内部令牌被拒绝")
    void internalTokenWithWrongAudienceIsRejected() throws Exception {
        String token = internalTokenService.generateToken(
                "smart-recruit-talent", "smart-recruit-system", List.of("offers.read"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/analytics/offer-stats");
        request.addHeader("Authorization", "Bearer " + token);

        doFilter(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "aud 不匹配的令牌不得在本服务建立身份");
    }

    @Test
    @DisplayName("用户 JWT 仍按角色与权限码建立身份")
    void userJwtStillAuthenticates() throws Exception {
        SecretKey key = JwtUtil.getSecretKey(USER_JWT_SECRET_BASE64);
        String userToken = JwtUtil.createAccessToken(key, 42L, "hr01", "hr01@example.com", "HR", "offer.read");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/offers/approvals/stats");
        request.addHeader("Authorization", "Bearer " + userToken);

        doFilter(request);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("hr01@example.com", authentication.getName(), "存在 email 时 principal 使用 email");
        assertEquals("42", authentication.getCredentials());
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertTrue(authorities.contains("ROLE_HR"));
        assertTrue(authorities.contains("offer.read"));
    }

    @Test
    @DisplayName("非法令牌不作为任何身份")
    void garbageTokenLeavesNoAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/offers/approvals/stats");
        request.addHeader("Authorization", "Bearer not-a-jwt");

        doFilter(request);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
