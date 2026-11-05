package com.smartrecruit.recruitment.filter;

import com.smartrecruit.common.constant.Constants;
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
 * 认证过滤器（Recruitment 微服务）。
 *
 * <p>本服务自行解析并校验 JWT，用户身份完全来自 Token 签名内容。
 * 不再读取 {@code X-User-Id} / {@code X-Username} / {@code X-User-Role} 等请求头，
 * 避免客户端伪造身份。未携带或校验失败时不设置认证上下文，
 * 由 Spring Security 的授权规则决定是否拒绝请求。</p>
 *
 * @since 1.0.0
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null) {
            try {
                SecretKey secretKey = JwtUtil.getSecretKey(jwtSecret);
                if (JwtUtil.isTokenValid(secretKey, token)) {
                    Claims claims = JwtUtil.parseToken(secretKey, token);
                    String userId = claims.getSubject();
                    String username = claims.get("username", String.class);
                    String role = claims.get("role", String.class);
                    String permissions = claims.get("permissions", String.class);

                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    if (role != null && !role.isEmpty() && !"REFRESH".equals(role)) {
                        Arrays.stream(role.split(","))
                                .filter(r -> !r.isBlank())
                                .map(r -> new SimpleGrantedAuthority(Constants.ROLE_PREFIX + r))
                                .forEach(authorities::add);
                    }
                    if (permissions != null && !permissions.isEmpty()) {
                        Arrays.stream(permissions.split(","))
                                .filter(p -> !p.isBlank())
                                .map(SimpleGrantedAuthority::new)
                                .forEach(authorities::add);
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    username != null ? username : userId,
                                    userId,
                                    authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT 认证成功: userId={}", userId);
                }
            } catch (Exception e) {
                log.warn("JWT 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头提取 Bearer Token。
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(Constants.TOKEN_HEADER);
        if (StringUtils.hasText(bearerToken)
                && bearerToken.startsWith(Constants.TOKEN_TYPE_BEARER + " ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
