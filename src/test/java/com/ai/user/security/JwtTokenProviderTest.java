package com.ai.user.security;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JwtTokenProvider} 单元测试：role claim 签发/解析往返、
 * 无 role claim 的旧令牌兼容(默认 USER)、非法令牌拒绝。
 */
class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(new AppProperties());

    @Test
    void roleClaimRoundTrip() {
        String token = provider.createToken(7L, "alice", UserContext.ROLE_ADMIN);

        JwtTokenProvider.TokenPayload payload = provider.parse(token);

        assertEquals(7L, payload.userId());
        assertEquals("alice", payload.username());
        assertEquals(UserContext.ROLE_ADMIN, payload.role());
    }

    @Test
    void legacyTokenWithoutRoleDefaultsToUser() {
        // 模拟旧版本令牌: 无 role claim
        var key = Keys.hmacShaKeyFor(
                new AppProperties().getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        String legacy = Jwts.builder()
                .subject("bob")
                .claim("uid", 8L)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 60_000))
                .signWith(key)
                .compact();

        JwtTokenProvider.TokenPayload payload = provider.parse(legacy);

        assertEquals(8L, payload.userId());
        assertEquals(UserContext.ROLE_USER, payload.role());
    }

    @Test
    void invalidTokenIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> provider.parse("not-a-jwt"));
        assertEquals(ErrorCode.TOKEN_INVALID, e.getErrorCode());
    }
}
