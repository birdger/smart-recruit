package com.smartrecruit.gateway.filter;

import com.smartrecruit.common.util.JwtUtil;
import com.smartrecruit.gateway.config.AuthSkipProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthFilter 请求头清理的单元测试。
 *
 * <p>针对 4B-3.4.1 阶段引入的 {@code INNER-REQUEST} 头清理逻辑，覆盖：
 * INNER-REQUEST 各种取值、HTTP 头名大小写、重复值、不破坏正常 JWT、
 * 不误删普通业务头、原有 {@code X-User-*} 清理行为不退化、
 * 白名单路径也清理、无 Token 匿名请求也清理。
 *
 * <p>Spring Framework 7.0 / Spring Boot 4.1 中 {@link HttpHeaders}
 * 不再实现 {@link java.util.Map} / {@link org.springframework.util.MultiValueMap}
 * 接口，{@code containsKey} / {@code contains} 均已移除。
 * 检查头是否存在使用 {@code headers.get("name") != null}，
 * 获取头第一个值使用 {@link HttpHeaders#getFirst(String)}。
 */
@DisplayName("AuthFilter 请求头清理")
class AuthFilterHeaderSanitizationTest {

    /**
     * 测试专用的 Base64 HS256 密钥，仅用于单元测试 Token 生成与验签，
     * 与生产配置中的真实密钥完全隔离。
     */
    private static final String TEST_SECRET_BASE64 =
            "dGVzdC1zZWNyZXQta2V5LWZvci1hdXRoLWZpbHRlci10ZXN0aW5n";

    private AuthFilter filter;
    private SecretKey secretKey;
    private AuthSkipProperties skipProperties;

    @BeforeEach
    void setUp() {
        secretKey = JwtUtil.getSecretKey(TEST_SECRET_BASE64);
        skipProperties = new AuthSkipProperties();
        filter = new AuthFilter(TEST_SECRET_BASE64, skipProperties);
    }

    /**
     * 构造一个记录了 {@link ServerWebExchange} 经过 filter 之后实际传递的
     * {@link ServerHttpRequest} 的 chain stub，方便断言 header 是否已被清理。
     */
    private GatewayFilterChain capturingChain(AtomicReference<ServerHttpRequest> captured) {
        return exchange -> {
            captured.set(exchange.getRequest());
            return Mono.empty();
        };
    }

    /**
     * 构造 Mock 请求：使用 String 形式的 URI，避免 Spring 7.0
     * {@link MockServerHttpRequest} Builder 泛型签名在不同调用方式下
     * 的类型歧义。
     */
    private MockServerHttpRequest buildGet(String uri) {
        return MockServerHttpRequest.get(uri).build();
    }

    private MockServerHttpRequest buildPost(String uri) {
        return MockServerHttpRequest.post(uri).build();
    }

    private MockServerHttpRequest buildOptions(String uri) {
        return MockServerHttpRequest.options(uri).build();
    }

    // ========================= INNER-REQUEST 清理 =========================

    @Test
    @DisplayName("外部请求携带 INNER-REQUEST: true 时，该头被清理")
    void sanitizeInnerRequestHeader_whenValueIsTrue() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/1")
                .header("INNER-REQUEST", "true")
                .header(HttpHeaders.ACCEPT, "application/json")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        GatewayFilterChain chain = capturingChain(captured);

        filter.filter(exchange, chain).block();

        ServerHttpRequest downstream = captured.get();
        assertNotNull(downstream);
        assertFalse(downstream.getHeaders().get("INNER-REQUEST") != null,
                "INNER-REQUEST: true 应被清理");
        assertEquals("application/json", downstream.getHeaders().getFirst(HttpHeaders.ACCEPT),
                "普通 Accept 头应保留");
    }

    @Test
    @DisplayName("外部请求携带 INNER-REQUEST: false 时，该头同样被清理（任何值都清）")
    void sanitizeInnerRequestHeader_whenValueIsFalse() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/1")
                .header("INNER-REQUEST", "false")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        assertFalse(captured.get().getHeaders().get("INNER-REQUEST") != null);
    }

    @Test
    @DisplayName("INNER-REQUEST 头名小写（HTTP 头名大小写不敏感）仍被清理")
    void sanitizeInnerRequestHeader_whenHeaderNameLowercase() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/1")
                .header("inner-request", "true")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        // Spring HttpHeaders 内部对键做规范化，这里检查任一大小写变体都不应存在
        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null || headers.get("inner-request") != null,
                "HTTP 头大小写不敏感，框架应统一处理");
    }

    @Test
    @DisplayName("INNER-REQUEST 重复值被一并清理")
    void sanitizeInnerRequestHeader_whenDuplicated() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/1")
                .header("INNER-REQUEST", "true")
                .header("INNER-REQUEST", "true")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        assertFalse(captured.get().getHeaders().get("INNER-REQUEST") != null,
                "重复的 INNER-REQUEST 头也应被清理");
    }

    // ========================= 原有 X-User-* 清理不退化 =========================

    @Test
    @DisplayName("原有 X-User-Id / X-Username / X-User-Role 伪造头仍然被清理")
    void sanitizeExistingIdentityHeaders() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/page")
                .header("X-User-Id", "99999")
                .header("X-Username", "hacker@evil.com")
                .header("X-User-Role", "ADMIN")
                .header("X-Request-Id", "req-keep-me")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("X-User-Id") != null);
        assertFalse(headers.get("X-Username") != null);
        assertFalse(headers.get("X-User-Role") != null);
        assertEquals("req-keep-me", headers.getFirst("X-Request-Id"),
                "普通业务头不应被清理");
    }

    // ========================= 不破坏有效 JWT 认证 =========================

    @Test
    @DisplayName("携带有效 JWT 的请求在清理 INNER-REQUEST 后，网关仍能注入可信身份头")
    void validJwtStillWorks_afterSanitization() throws Exception {
        String token = JwtUtil.createAccessToken(secretKey, 1001L,
                "testuser", "test@example.com", "RECRUITER", "");

        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/page")
                .header("INNER-REQUEST", "true")
                .header("X-User-Id", "666")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null,
                "INNER-REQUEST 应被清理");
        // X-User-Id 应被验证后的可信值覆盖：不能同时是 666 且存在
        assertTrue(headers.get("X-User-Id") != null && !"666".equals(headers.getFirst("X-User-Id")),
                "客户端伪造的 X-User-Id 应被覆盖");
        assertEquals("1001", headers.getFirst("X-User-Id"),
                "验签后应注入可信 userId");
        assertEquals("testuser", headers.getFirst("X-Username"));
        assertEquals("RECRUITER", headers.getFirst("X-User-Role"));
    }

    // ========================= 匿名请求也清理 =========================

    @Test
    @DisplayName("无 Token 的匿名请求也清理 INNER-REQUEST（不授予任何身份）")
    void anonymousRequestAlsoSanitizesInnerRequest() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/page")
                .header("INNER-REQUEST", "true")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null);
        assertFalse(headers.get("X-User-Id") != null, "匿名请求不应注入身份头");
        assertFalse(headers.get("X-Username") != null);
        assertFalse(headers.get("X-User-Role") != null);
    }

    // ========================= 白名单路径也清理 =========================

    @Test
    @DisplayName("白名单公开接口（跳过认证）也必须清理 INNER-REQUEST")
    void whitelistPathStillSanitizesInnerRequest() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://example.com/api/v1/auth/login")
                .header("INNER-REQUEST", "true")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null,
                "即使是白名单路径也必须清理 INNER-REQUEST");
        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                headers.getFirst(HttpHeaders.CONTENT_TYPE),
                "业务头不应被清理");
    }

    @Test
    @DisplayName("OPTIONS 预检请求也清理 INNER-REQUEST")
    void optionsRequestSanitizesInnerRequest() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .options("http://example.com/api/v1/users/page")
                .header("INNER-REQUEST", "true")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null);
    }

    // ========================= 不误删其他普通请求头 =========================

    @Test
    @DisplayName("清理 INNER-REQUEST 不影响 Cookie、Content-Type、Accept 等普通业务头")
    void ordinaryHeadersPreserved() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/page")
                .header("INNER-REQUEST", "true")
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Request-Id", "req-abc-123")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, capturingChain(captured)).block();

        HttpHeaders headers = captured.get().getHeaders();
        assertFalse(headers.get("INNER-REQUEST") != null);
        assertEquals("application/json", headers.getFirst(HttpHeaders.ACCEPT));
        assertEquals("application/json", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("req-abc-123", headers.getFirst("X-Request-Id"));
    }

    // ========================= 无效 Token 场景 =========================

    @Test
    @DisplayName("无效 Token 时 INNER-REQUEST 仍被清理，且返回错误而不是提权")
    void invalidToken_rejects_withoutInnerRequestHeader() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("http://example.com/api/v1/users/page")
                .header("INNER-REQUEST", "true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer definitely-invalid-token-xxx")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // 构造会记录请求的 chain（理论上不会被调用，因为无效 Token 直接返回）
        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        GatewayFilterChain chain = capturingChain(captured);

        filter.filter(exchange, chain).block();

        // 过滤器在 Token 无效时直接写错误响应，不会走到 chain
        assertNull(captured.get(), "无效 Token 不应到达下游");
        // 关键点：即使响应为业务错误，下游过滤器也不会收到 INNER-REQUEST
        // （因为 stripIdentityHeaders 是 filter 第一行，早于 Token 校验）
    }
}
