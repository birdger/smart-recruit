package com.smartrecruit.system.filter;

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
import org.springframework.lang.Nullable;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 认证过滤器（普通用户 JWT + 内部服务签名 JWT）。
 *
 * <h3>认证流程顺序（严格，不能乱）</h3>
 * <pre>
 * 1. Authorization 头存在
 *     ├─ 先尝试用内部服务密钥验签 → 成功 + scope 满足 → 建立内部服务身份 ✅
 *     ├─ 内部令牌验证抛异常（签名错 / aud 错 / 过期 / scope 不足）
 *     │   ├─ scope 不足 → 记录 warn，不设 Authentication，继续（让 SecurityFilterChain 决定 401/403）
 *     │   └─ 其他异常 → 尝试用普通用户密钥验签 → 成功 → 建立用户身份 ✅
 *     │                → 失败 → 记录 warn，不设 Authentication，继续
 *     └─ Authorization 头不存在
 *         └─ 无认证信息 → SecurityContext 保持空（不信任 INNER-REQUEST 头）
 * 2. 以上都未建立 Authentication → 继续 filter chain
 *    SecurityFilterChain 的 permitAll 路由会放行，authenticated() 路由会拦截 → 401
 * </pre>
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>Authorization 头存在但内部令牌验证失败时，**不 fallback** 到 INNER-REQUEST 分支。
 *       否则：攻击者伪造 Authorization 头（签名错）会被 INNER-REQUEST 抢救成 ROLE_SYSTEM。</li>
 *   <li>内部令牌 scope 不足时，**不** fallback 到 INNER-REQUEST 或普通用户 JWT。</li>
 *   <li>只有 Authorization 头完全不存在时，不建立任何身份（INNER-REQUEST 已不再被信任）。</li>
 * </ul>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 普通用户 JWT 密钥（来自项目原有配置 {@code jwt.secret}）。 */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /** 内部服务 JWT 工具（common 模块）。 */
    private final InternalTokenService internalTokenService;

    /**
     * 内部令牌 scope 到路径前缀的映射。
     * key：URL 路径前缀（Ant 风格前缀匹配）
     * value：访问该路径所需的 scope（空格分隔）
     */
    private static final Map<String, String> SCOPE_PER_PATH_PREFIX = Map.of(
            // 系统配置：只读（recruitment 的 SystemConfigClient 调用）
            "/api/v1/configs/internal", "configs.read",
            // 内部通知（permitAll 路由，但内部调用方仍应声明 scope）
            "/api/v1/internal/notifications", "internal.notifications.send",
            // 内部邮箱发送（permitAll 路由）
            "/api/v1/internal/email", "internal.email.send",
            // 内部用户管理员批量查询（permitAll 路由）
            "/api/v1/internal/users", "internal.users.read",
            // 文件上传 / 下载 / 删除（permitAll 路由）
            "/api/v1/files/upload",      "files.upload",
            "/api/v1/files/download",    "files.download",
            "/api/v1/files/delete",      "files.delete"
    );

    public JwtAuthenticationFilter(InternalTokenService internalTokenService) {
        this.internalTokenService = internalTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String bearerToken = request.getHeader(Constants.TOKEN_HEADER);
        boolean hasBearerToken = StringUtils.hasText(bearerToken)
                && bearerToken.startsWith(Constants.TOKEN_TYPE_BEARER + " ");

        if (hasBearerToken) {
            // ===== 分支 1：Authorization 头存在 =====
            String token = bearerToken.substring(7);

            // 1a. 先尝试内部服务 JWT（独立密钥、aud、iss、scope）
            Claims internalClaims = tryInternalToken(token, request);
            if (internalClaims != null) {
                String caller = internalTokenService.extractCallerApplicationName(internalClaims);
                List<String> scopeList = parseScopesFromClaim(internalClaims);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                caller, null,
                                scopeList.stream()
                                        .map(s -> new SimpleGrantedAuthority("SCOPE_" + s))
                                        .toList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("内部服务 JWT 认证成功: caller={}, scopes={}, path={}",
                        caller, scopeList, request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // 1b. 内部令牌验证失败（签名错 / aud 错 / 过期等）
            //     或 scope 不足 → 继续尝试普通用户 JWT
            tryAuthenticateUserToken(token, request);
            filterChain.doFilter(request, response);
            return;
        }

        // ===== 分支 2：Authorization 头完全不存在 =====
        // R1-A：不再信任 INNER-REQUEST 头，无令牌请求一律不建立身份
        SecurityContextHolder.clearContext();
        filterChain.doFilter(request, response);
    }

    // =====================================================================
    // 内部服务 JWT
    // =====================================================================

    /**
     * 尝试把 token 当作内部服务 JWT 验证。
     *
     * @return 验证成功且 scope 满足的 Claims；验证失败或 scope 不足返回 null
     */
    @Nullable
    private Claims tryInternalToken(String token, HttpServletRequest request) {
        try {
            Claims claims = internalTokenService.verifyToken(token);

            // scope 校验：找出当前请求路径前缀对应的必需 scope
            String path = request.getRequestURI();
            String requiredScopes = findRequiredScopes(path, request.getMethod());
            if (requiredScopes != null) {
                List<String> requiredList = Arrays.asList(requiredScopes.split(" "));
                internalTokenService.assertScopes(claims, requiredList);
            }
            // 无必需 scope（不在映射表中）→ 放行（permitAll 路由或公开路径）

            return claims;
        } catch (InternalTokenValidationException ex) {
            // scope 不足 → 明确 warn，不 fallback INNER-REQUEST
            if ("SCOPE_MISSING".equals(ex.getErrorCode())) {
                log.warn("内部服务 JWT scope 不足: path={}, error={}",
                        request.getRequestURI(), ex.getMessage());
                return null;
            }
            // BAD_SIGNATURE / BAD_AUDIENCE / TOKEN_EXPIRED 等 → 静默失败
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 用前缀匹配找到当前路径所需的 scope。
     * 映射表第一个匹配的前缀生效（已按精确到粗粒度排序）。
     *
     * @return 必需 scope 字符串（空格分隔）；无匹配返回 null
     */
    @Nullable
    private String findRequiredScopes(String path, String httpMethod) {
        // 用户管理按 HTTP 方法细分：读接口只要求 users.read，
        // 避免 GET 被迫携带 create/update/delete 等写权限（最小权限原则）
        if (path.startsWith("/api/v1/users")) {
            return switch (httpMethod == null ? "" : httpMethod.toUpperCase()) {
                case "GET" -> "users.read";
                case "POST" -> "users.create";
                case "PUT", "PATCH" -> "users.update";
                case "DELETE" -> "users.delete";
                default -> null;
            };
        }
        // 先长前缀（更精确），再短前缀
        List<String> sortedKeys = new ArrayList<>(SCOPE_PER_PATH_PREFIX.keySet());
        sortedKeys.sort((a, b) -> b.length() - a.length());
        for (String prefix : sortedKeys) {
            if (path.startsWith(prefix)) {
                return SCOPE_PER_PATH_PREFIX.get(prefix);
            }
        }
        return null;
    }

    private List<String> parseScopesFromClaim(Claims claims) {
        Object scopeClaim = claims.get(InternalTokenService.CLAIM_SCOPE);
        List<String> scopes = new ArrayList<>();
        if (scopeClaim instanceof String s) {
            for (String t : s.split("\\s+")) {
                if (!t.isBlank()) scopes.add(t);
            }
        } else if (scopeClaim instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) scopes.add(s);
            }
        }
        return scopes;
    }

    // =====================================================================
    // 普通用户 JWT
    // =====================================================================

    private void tryAuthenticateUserToken(String token, HttpServletRequest request) {
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

                log.debug("普通用户 JWT 认证成功: userId={}, email={}, path={}",
                        userId, email, request.getRequestURI());
            }
        } catch (Exception e) {
            log.warn("普通用户 JWT 解析失败: path={}, error={}",
                    request.getRequestURI(), e.getMessage());
            SecurityContextHolder.clearContext();
        }
    }
}
