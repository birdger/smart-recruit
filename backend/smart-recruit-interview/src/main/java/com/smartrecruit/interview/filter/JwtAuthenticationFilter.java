package com.smartrecruit.interview.filter;

import com.smartrecruit.common.constant.Constants;
import com.smartrecruit.common.security.InternalTokenService;
import com.smartrecruit.common.security.InternalTokenValidationException;
import com.smartrecruit.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 认证过滤器（Interview 微服务）。
 *
 * <p>只信任两种<b>经过验签</b>的令牌，不接受任何可被客户端伪造的身份请求头：
 * <ol>
 *   <li>服务间内部令牌（{@link InternalTokenService} 签发；校验签名、{@code iss} 前缀、
 *       {@code aud}=本服务名、{@code sub}、有效期），映射为 {@code SCOPE_*} 权限，
 *       principal 为调用方服务名，credentials 为空 —— 内部身份不会被当成普通用户 ID；</li>
 *   <li>用户 JWT（与网关同密钥、同签发方），映射为 {@code ROLE_*} 与权限码，
 *       credentials 为字符串形式的 userId，供 {@code UserContextUtil#getCurrentUserId()} 读取。</li>
 * </ol>
 *
 * <p>令牌来源顺序与网关保持一致，避免破坏既有前端调用方式：
 * {@code Authorization: Bearer} → {@code access_token} Cookie → {@code ?token=} 查询参数（SSE）。
 *
 * <p>安全边界：
 * <ul>
 *   <li>不读取 {@code X-User-Id} / {@code X-Username} / {@code X-User-Role}；直连端口伪造这些头
 *       不会建立任何身份。</li>
 *   <li>不读取 {@code INNER-REQUEST}；该头在验签通过前不产生任何权限。</li>
 *   <li>任何校验失败都不会回退到匿名或高权限身份，SecurityContext 保持为空，
 *       由 SecurityFilterChain 决定 401/403。</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 网关在 Cookie 中保存 Token 的键名（与网关 AuthFilter 保持一致）。 */
    private static final String COOKIE_ACCESS_TOKEN = "access_token";

    /** 网关支持的查询参数 Token（用于 EventSource 等无法自定义请求头的场景）。 */
    private static final String QUERY_PARAM_TOKEN = "token";

    private final InternalTokenService internalTokenService;
    private final String jwtSecret;

    public JwtAuthenticationFilter(InternalTokenService internalTokenService,
                                   @Value("${jwt.secret}") String jwtSecret) {
        this.internalTokenService = internalTokenService;
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            // 无任何令牌：不建立身份（不信任 X-User-* / INNER-REQUEST）
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        // 1) 先尝试服务间内部令牌（独立密钥 + aud + scope）
        if (authenticateInternalToken(token, request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2) 再尝试用户 JWT（验证失败时保持空上下文）
        authenticateUserToken(token, request);
        filterChain.doFilter(request, response);
    }

    /**
     * 校验服务间内部令牌并写入认证上下文。
     *
     * @param token   候选令牌
     * @param request 当前请求（仅用于日志）
     * @return {@code true} 表示已按内部令牌完成认证；{@code false} 表示不是合法内部令牌，
     *         调用方应继续按用户 JWT 校验
     */
    private boolean authenticateInternalToken(String token, HttpServletRequest request) {
        try {
            Claims claims = internalTokenService.verifyToken(token);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            Object scopeClaim = claims.get(InternalTokenService.CLAIM_SCOPE);
            if (scopeClaim instanceof String scopeText) {
                Arrays.stream(scopeText.split("\\s+"))
                        .filter(scope -> !scope.isBlank())
                        .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                        .forEach(authorities::add);
            }

            String caller = internalTokenService.extractCallerApplicationName(claims);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            caller != null ? caller : "internal-service",
                            null,
                            authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("内部服务令牌认证成功: caller={}, scopes={}, path={}",
                    caller, scopeClaim, request.getRequestURI());
            return true;
        } catch (InternalTokenValidationException e) {
            // 不是内部令牌（通常是网关签发的用户 JWT），继续走用户 JWT 校验
            log.debug("非内部服务令牌: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("内部令牌校验异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 校验用户 JWT 并写入认证上下文；任何失败都保持空上下文。
     */
    private void authenticateUserToken(String token, HttpServletRequest request) {
        try {
            SecretKey secretKey = JwtUtil.getSecretKey(jwtSecret);
            if (!JwtUtil.isTokenValid(secretKey, token)) {
                SecurityContextHolder.clearContext();
                return;
            }
            Claims claims = JwtUtil.parseToken(secretKey, token);
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            String permissions = claims.get("permissions", String.class);

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (role != null && !role.isEmpty() && !"REFRESH".equals(role)) {
                Arrays.stream(role.split(","))
                        .map(r -> new SimpleGrantedAuthority(Constants.ROLE_PREFIX + r))
                        .forEach(authorities::add);
            }
            if (permissions != null && !permissions.isEmpty()) {
                Arrays.stream(permissions.split(","))
                        .filter(p -> !p.isEmpty())
                        .map(SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }

            String principal = email != null ? email : username;
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, userId, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("用户 JWT 认证成功: userId={}, path={}", userId, request.getRequestURI());
        } catch (Exception e) {
            log.warn("用户 JWT 解析失败: path={}, error={}", request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 按网关一致的顺序提取令牌。
     *
     * @param request HTTP 请求
     * @return 令牌字符串；未提供返回 {@code null}
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(Constants.TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken)
                && bearerToken.startsWith(Constants.TOKEN_TYPE_BEARER + " ")) {
            return bearerToken.substring(Constants.TOKEN_TYPE_BEARER.length() + 1).trim();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_ACCESS_TOKEN.equals(cookie.getName())
                        && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }

        String queryToken = request.getParameter(QUERY_PARAM_TOKEN);
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }
}
