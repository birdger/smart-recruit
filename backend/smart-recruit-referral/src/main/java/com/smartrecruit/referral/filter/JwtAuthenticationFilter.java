package com.smartrecruit.referral.filter;

import com.smartrecruit.common.constant.Constants;
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
 * 认证过滤器（Referral 微服务）。
 *
 * <p>安全修复（A3-1）：<b>不再信任</b> {@code X-User-Id} / {@code X-Username} / {@code X-User-Role}。
 * 原有实现优先采信这些由网关注入的请求头，但业务端口可被直连绕过网关，
 * 伪造请求头即可建立任意用户身份并影响 {@code @PreAuthorize} 判定。
 *
 * <p>现在身份只来自<b>验签成功</b>的用户 JWT：校验签名与有效期后，
 * 由 {@code role} 声明生成 {@code ROLE_*} 权限、由 {@code permissions} 声明生成权限码，
 * credentials 为字符串 userId（保持 {@code UserContextUtil#getCurrentUserId()} 语义），
 * principal 为用户名（保持 {@code getCurrentUserName()} 语义）。
 *
 * <p>令牌来源与网关一致：{@code Authorization: Bearer} → {@code access_token} Cookie → {@code ?token=}。
 * 公开端点（网关白名单）在未携带令牌时保持匿名，不影响既有匿名访问行为。
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

    private final SecretKey secretKey;

    public JwtAuthenticationFilter(@Value("${jwt.secret}") String jwtSecret) {
        this.secretKey = JwtUtil.getSecretKey(jwtSecret);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token == null) {
            // 无令牌：不建立身份（伪造 X-User-* 不再生效）
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (!JwtUtil.isTokenValid(secretKey, token)) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            Claims claims = JwtUtil.parseToken(secretKey, token);
            String userId = claims.getSubject();
            if (userId == null || userId.isEmpty()) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            String permissions = claims.get("permissions", String.class);
            setAuthentication(userId, username, role, permissions);
            log.debug("JWT 认证成功: userId={}, path={}", userId, request.getRequestURI());
        } catch (Exception e) {
            log.debug("JWT 校验失败（不建立身份）: path={}, error={}",
                    request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(String userId, String username, String role, String permissions) {
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

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        username != null ? username : userId,
                        userId,
                        authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);
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
