package com.smartrecruit.common.security;

import com.smartrecruit.common.constant.Constants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内部服务之间使用的签名 JWT 工具类。
 *
 * <p>与 {@link com.smartrecruit.common.util.JwtUtil} 处理普通用户 JWT 的设计解耦：
 * 内部令牌使用独立的密钥（{@code smart-recruit.internal.secret}）、
 * 独立的签发方（"internal-service-caller"）、独立的有效期（默认 15 分钟），
 * 不共享用户令牌的密钥和语义。
 *
 * <p>内部令牌的 claims 结构：
 * <pre>
 * {
 *   "iss": "smart-recruit-recruitment",        // 调用方服务名
 *   "aud": "smart-recruit-system",              // 目标服务名
 *   "sub": "internal-service-call",
 *   "scope": "users.read configs.read",         // 空格分隔的细粒度权限
 *   "iat": 1729632000,
 *   "exp": 1729632900                           // 默认 15 min
 * }
 * </pre>
 *
 * <p>安全约束：
 * <ul>
 *   <li>签名算法强制 HS256（{@link io.jsonwebtoken.SignatureAlgorithm#HS256}），
 *       拒绝 {@code none} 或其他算法降级。
 *   <li>验证时校验 {@code aud} 必须等于当前服务的
 *       {@code application.name}（由目标服务注入），避免令牌被跨服务滥用。
 *   <li>验证时检查 {@code scope} 包含所需权限，不是一刀切授予
 *       {@code ROLE_SYSTEM}。
 *   <li>签名密钥不从源码硬编码，由外部配置注入。
 * </ul>
 *
 * @see com.smartrecruit.common.util.JwtUtil
 */
@Component
public class InternalTokenService {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenService.class);

    /** 内部令牌默认有效期（秒），15 分钟。 */
    public static final long DEFAULT_EXPIRE_SECONDS = 900L;

    /** 内部令牌签发方标识。 */
    public static final String ISSUER_PREFIX = "internal-service-caller:";

    /** 内部令牌 {@code sub} 固定值，与用户 JWT 区分。 */
    public static final String SUBJECT_INTERNAL = "internal-service-call";

    /** 内部令牌 {@code alg} 强制值。 */
    public static final String REQUIRED_ALG = "HS256";

    /** scope claims 键。 */
    public static final String CLAIM_SCOPE = "scope";

    /**
     * 密钥来源：Base64 字符串。
     *
     * <p><b>不提供代码内置默认值</b>：缺失配置时 Spring 启动即失败（fail-fast），
     * 避免生产环境静默回退到源码中可预测的固定密钥。
     * 各模块 {@code application.yml} 通过 {@code ${INTERNAL_JWT_SECRET:...}} 提供可覆盖的配置。
     */
    @Value("${smart-recruit.internal.secret}")
    private String secretKeyBase64;

    /** 当前服务的 application.name，用于校验内部令牌的 {@code aud}。 */
    @Value("${spring.application.name:smart-recruit-common}")
    private String currentApplicationName;

    private volatile SecretKey cachedKey;

    // =====================================================================
    // 密钥解析
    // =====================================================================

    private SecretKey getSecretKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        synchronized (this) {
            if (cachedKey != null) {
                return cachedKey;
            }
            try {
                byte[] keyBytes = Base64.getDecoder().decode(secretKeyBase64);
                SecretKey key = Keys.hmacShaKeyFor(keyBytes);
                // 缓存，一次解析
                cachedKey = key;
                return key;
            } catch (IllegalArgumentException | WeakKeyException ex) {
                throw new IllegalStateException(
                        "Invalid internal service JWT secret key configuration: " + ex.getMessage(), ex);
            }
        }
    }

    // =====================================================================
    // 签发
    // =====================================================================

    /**
     * 签发内部服务 JWT。
     *
     * @param callerApplicationName 调用方的 {@code application.name}
     *                               （例如 "smart-recruit-recruitment"）
     * @param targetApplicationName 目标服务的 {@code application.name}
     *                               （例如 "smart-recruit-system"）
     * @param scopes                本次调用需要的细粒度权限集合
     *                               （例如 ["users.read", "configs.read"]）
     * @return 紧凑格式 JWT 字符串
     * @throws IllegalArgumentException 如果任一必填参数为空或 scopes 为空
     */
    public String generateToken(String callerApplicationName,
                                String targetApplicationName,
                                List<String> scopes) {
        if (callerApplicationName == null || callerApplicationName.isBlank()) {
            throw new IllegalArgumentException("callerApplicationName must not be blank");
        }
        if (targetApplicationName == null || targetApplicationName.isBlank()) {
            throw new IllegalArgumentException("targetApplicationName must not be blank");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }

        long nowSec = System.currentTimeMillis() / 1000;
        long expSec = nowSec + DEFAULT_EXPIRE_SECONDS;

        String scopeJoined = String.join(" ", scopes);
        String issuer = ISSUER_PREFIX + callerApplicationName;

        return Jwts.builder()
                .issuer(issuer)
                .subject(SUBJECT_INTERNAL)
                .audience().add(targetApplicationName).and()
                .issuedAt(new java.util.Date(nowSec * 1000L))
                .expiration(new java.util.Date(expSec * 1000L))
                .claim(CLAIM_SCOPE, scopeJoined)
                .signWith(getSecretKey())
                .compact();
    }

    // =====================================================================
    // 验证
    // =====================================================================

    /**
     * 验证内部服务 JWT 的完整性和合法性。
     *
     * <p>成功时返回解析后的 Claims 供后续权限检查使用；
     * 任何验证失败抛出 {@link InternalTokenValidationException}，
     * 由调用方决定返回 401 或 403。
     *
     * <p>验证项：
     * <ol>
     *   <li>格式合法（能解析为紧凑 JWS）</li>
     *   <li>签名有效</li>
     *   <li>过期时间 {@code exp} 未到期</li>
     *   <li>签发方 {@code iss} 带有 {@link #ISSUER_PREFIX}</li>
     *   <li>受众 {@code aud} 等于当前服务的 {@code application.name}</li>
     *   <li>主题 {@code sub} 等于 {@link #SUBJECT_INTERNAL}</li>
     * </ol>
     *
     * @param token 待验证的 JWT 字符串
     * @return 解析后的 Claims
     * @throws InternalTokenValidationException 任何验证失败
     */
    public Claims verifyToken(String token) throws InternalTokenValidationException {
        if (token == null || token.isBlank()) {
            throw new InternalTokenValidationException("Internal token must not be blank", "TOKEN_BLANK");
        }
        try {
            // 先验签，底层已经强制 HS256（由 Keys.hmacShaKeyFor + 签名方法决定）
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();

            // 1. 签发方校验
            String issuer = claims.getIssuer();
            if (issuer == null || !issuer.startsWith(ISSUER_PREFIX)) {
                throw new InternalTokenValidationException(
                        "Internal token issuer invalid or missing prefix: " + issuer, "BAD_ISSUER");
            }

            // 2. 受众校验（aud 必须等于当前服务名，防止跨服务滥用）
            Set<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(currentApplicationName)) {
                throw new InternalTokenValidationException(
                        "Internal token audience does not match current service: " + aud
                                + " (expected " + currentApplicationName + ")",
                        "BAD_AUDIENCE");
            }

            // 3. 主题校验
            String subject = claims.getSubject();
            if (!SUBJECT_INTERNAL.equals(subject)) {
                throw new InternalTokenValidationException(
                        "Internal token subject unexpected: " + subject, "BAD_SUBJECT");
            }

            // 4. 过期（jjwt parser 已经自动校验，但这里显式再查一次以便输出明确的错误码）
            java.util.Date exp = claims.getExpiration();
            if (exp != null && exp.before(new java.util.Date())) {
                throw new InternalTokenValidationException("Internal token is expired", "TOKEN_EXPIRED");
            }

            return claims;
        } catch (InternalTokenValidationException e) {
            throw e;
        } catch (Exception ex) {
            log.debug("Internal token validation failed: {}", ex.getMessage());
            throw new InternalTokenValidationException(
                    "Internal token failed signature or format validation", "BAD_SIGNATURE");
        }
    }

    /**
     * 检查已验证的 claims 中是否包含所有必需的 scope。
     *
     * <p>scope 以空格分隔的单字符串存储在
     * {@link #CLAIM_SCOPE} claims 中，本方法拆分为集合后做交集检查。
     *
     * @param claims 已通过 {@link #verifyToken} 验证的 claims
     * @param requiredScopes 本次调用需要的权限集合
     * @return {@code true} 表示全部必需权限都已授予
     * @throws InternalTokenValidationException 如果任何必需权限缺失
     */
    public boolean assertScopes(Claims claims, List<String> requiredScopes)
            throws InternalTokenValidationException {
        if (requiredScopes == null || requiredScopes.isEmpty()) {
            return true;
        }
        Object scopeClaim = claims.get(CLAIM_SCOPE);
        Set<String> grantedScopes = new HashSet<>();
        if (scopeClaim instanceof String s) {
            // 空格分隔
            for (String token : s.split("\\s+")) {
                if (!token.isBlank()) {
                    grantedScopes.add(token);
                }
            }
        } else if (scopeClaim instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    grantedScopes.add(s);
                }
            }
        }

        for (String required : requiredScopes) {
            if (!grantedScopes.contains(required)) {
                throw new InternalTokenValidationException(
                        "Internal token missing required scope: " + required
                                + " (granted=" + grantedScopes + ", required=" + requiredScopes + ")",
                        "SCOPE_MISSING");
            }
        }
        return true;
    }

    /**
     * 从已验证 claims 中提取调用方服务名。
     *
     * @param claims 已验证的 claims
     * @return 调用方 {@code application.name}
     */
    public String extractCallerApplicationName(Claims claims) {
        String issuer = claims.getIssuer();
        if (issuer != null && issuer.startsWith(ISSUER_PREFIX)) {
            return issuer.substring(ISSUER_PREFIX.length());
        }
        return null;
    }
}
