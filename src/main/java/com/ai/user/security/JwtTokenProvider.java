package com.ai.user.security;
import com.ai.user.security.UserContext;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 令牌提供者：签发/解析 HS256 令牌(claims: sub=用户名, uid=用户ID)。
 */
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long expireMillis;

    /**
     * 构造器：从配置读取密钥与有效期。
     *
     * @param appProperties 应用配置(app.auth.*)
     */
    public JwtTokenProvider(AppProperties appProperties) {
        byte[] keyBytes = appProperties.getAuth().getJwtSecret()
                .getBytes(StandardCharsets.UTF_8);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expireMillis = appProperties.getAuth().getTokenExpireHours() * 3600_000L;
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
