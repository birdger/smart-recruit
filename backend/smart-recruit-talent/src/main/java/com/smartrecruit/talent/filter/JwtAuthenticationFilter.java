package com.smartrecruit.talent.filter;

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
 * JWT 认证过滤器（Talent 微服务）。
 *
 * <p>安全修复（A3-1）：<b>不再信任</b> {@code X-User-Id} / {@code X-Username} / {@code X-User-Role}。
 * 这些请求头由网关注入，但业务服务端口可被直连绕过网关，攻击者伪造请求头即可建立任意身份。
 * 现在身份只能来自<b>验签成功</b>的令牌：
 * <ol>
 *   <li>服务间内部令牌 → {@code SCOPE_*} 权限，principal 为调用方服务名，credentials 为空；</li>
 *   <li>用户 JWT → {@code ROLE_*} + 权限码，credentials 为字符串 userId，
 *       业务侧 {@code UserContextUtil#getCurrentUserId()} 语义保持不变。</li>
 * </ol>
 *
 * <p>令牌来源与网关保持一致（{@code Authorization: Bearer} → {@code access_token} Cookie
 * → {@code ?token=}），以保证经网关的既有前端调用（含 Cookie 方式）行为不变。
 *
 * @since 1.0.0
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 网关在 Cookie 中保存 Token 的键名（与网关 AuthFilter 保持一致）。 */
    private static final String COOKIE_ACCESS_TOKEN = "access_token";

    /** 网关支持的查询参数 Token（EventSource 等无法自定义请求头的场景）。 */
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
            // 无令牌：不建立身份。伪造 X-User-* 不会生效。
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        if (authenticateInternalToken(token, request)) {
            filterChain.doFilter(request, response);
            return;
        }

        authenticateUserToken(token, request);
        filterChain.doFilter(request, response);
    }

    /**
     * 校验服务间内部令牌并写入认证上下文。
     *
     * @param token   候选令牌
     * @param request 当前请求（仅用于日志）
     * @return {@code true} 表示已按内部令牌完成认证
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

            String principal = username != null ? username : (email != null ? email : userId);
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
