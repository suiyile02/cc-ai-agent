package com.ai.user.security;
import com.ai.user.security.UserContext;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 令牌提供者：签发/解析 HS256 令牌(claims: sub=用户名, uid=用户ID)。
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** 随机密钥长度(字节), 与 prod 最短要求一致 */
    private static final int KEY_BYTES = 32;

    private final SecretKey signingKey;
    private final long expireMillis;

    /**
     * 构造器：从配置读取密钥与有效期。
     *
     * <p>仓库内不放任何默认密钥：{@code JWT_SECRET} 留空时非生产环境自动生成一次性随机密钥
     * （本地开发可正常起, 但重启即让旧 token 失效）；prod 下留空由
     * {@code SecurityConfigValidator} 拒绝启动。
     *
     * @param appProperties 应用配置(app.auth.*)
     */
    public JwtTokenProvider(AppProperties appProperties) {
        String configured = appProperties.getAuth().getJwtSecret();
        if (configured == null || configured.isBlank()) {
            configured = randomSecret();
            log.warn("JWT_SECRET 未配置, 已生成一次性随机密钥(仅限本地开发: 重启后旧 token 失效; prod 下留空将拒绝启动)");
        }
        byte[] keyBytes = configured.getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = appProperties.getAuth().getTokenExpireHours() * 3600_000L;
    }

    /** 生成 URL-safe Base64 的 32 字节随机密钥（仅用于本地开发兜底）。 */
    private static String randomSecret() {
        byte[] bytes = new byte[KEY_BYTES];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 签发令牌。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param role     角色(ADMIN/USER), 写入 role claim 供授权检查
     * @return JWT 串
     */
    public String createToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                // jti: 令牌唯一 ID, 注销/禁用时据此拉黑(A1 Token 吊销)
                .id(java.util.UUID.randomUUID().toString())
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并校验令牌, 返回负载(用户 ID + 用户名 + 角色)。
     *
     * @param token JWT 串
     * @return 令牌负载
     * @throws BusinessException TOKEN_INVALID(无效/过期/被篡改)
     */
    public TokenPayload parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object uid = claims.get("uid");
            if (!(uid instanceof Number n)) {
                throw new BusinessException(ErrorCode.TOKEN_INVALID);
            }
            // 兼容无 role claim 的旧令牌, 一律按普通用户处理
            Object role = claims.get("role");
            Date expiration = claims.getExpiration();
            return new TokenPayload(n.longValue(), claims.getSubject(),
                    role instanceof String r ? r : UserContext.ROLE_USER,
                    claims.getId(),
                    expiration == null ? 0 : expiration.getTime());
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "凭证无效或已过期，请重新登录");
        }
    }

    /**
     * 令牌负载。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param role     角色(ADMIN/USER)
     * @param jti      令牌唯一 ID(吊销黑名单键; 旧令牌可能为 null)
     * @param expiresAt 过期时间戳(epoch 毫秒; 0=未知)
     */
    public record TokenPayload(Long userId, String username, String role,
                               String jti, long expiresAt) {
    }
}
