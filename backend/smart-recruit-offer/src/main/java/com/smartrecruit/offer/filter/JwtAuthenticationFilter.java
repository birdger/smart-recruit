package com.smartrecruit.offer.filter;

import com.smartrecruit.common.constant.Constants;
import com.smartrecruit.common.security.InternalTokenService;
import com.smartrecruit.common.security.InternalTokenValidationException;
import com.smartrecruit.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
 * JWT 认证过滤器（Offer 微服务）。
 *
 * <p>只信任两种签名令牌，均从 {@code Authorization: Bearer} 请求头解析：
 * <ol>
 *   <li>服务间内部令牌（{@link InternalTokenService} 签发，{@code aud} 必须为本服务名），
 *       授予 {@code SCOPE_*} 权限并保留调用方服务名；</li>
 *   <li>用户 JWT（与网关同密钥签发），授予角色与权限码。</li>
 * </ol>
 *
 * <p>R1-A：不再信任客户端可伪造的 {@code INNER-REQUEST: true} 请求头。
 * 该头此前在本过滤器验签<b>之前</b>直接授予 {@code ROLE_SYSTEM}，
 * 与 System 模块的旧缺陷同源，已移除。
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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

        if (token != null) {
            // 1) 先尝试服务间内部令牌（签名 + aud 校验）
            if (authenticateInternalToken(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 2) 再尝试用户 JWT
            try {
                SecretKey secretKey = JwtUtil.getSecretKey(jwtSecret);
                if (JwtUtil.isTokenValid(secretKey, token)) {
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
                }
            } catch (Exception e) {
                log.warn("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 校验服务间内部令牌并写入认证上下文。
     *
     * <p>令牌由 {@link InternalTokenService} 签发，签名密钥、issuer、aud、
     * subject、有效期的校验全部在 {@code verifyToken} 内完成；
     * 本方法只负责把 {@code scope} claims 映射为 {@code SCOPE_*} 权限。
     *
     * @param token 候选令牌
     * @return {@code true} 表示已按内部令牌完成认证；{@code false} 表示不是合法内部令牌，
     *         调用方应继续按用户 JWT 校验
     */
    private boolean authenticateInternalToken(String token) {
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
            log.debug("内部服务令牌认证成功: caller={}, scopes={}", caller, scopeClaim);
            return true;
        } catch (InternalTokenValidationException e) {
            // 不是内部令牌（例如网关签发的用户 JWT），继续走用户 JWT 校验
            log.debug("非内部服务令牌: {}", e.getMessage());
            return false;
        }
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(Constants.TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(Constants.TOKEN_TYPE_BEARER + " ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
