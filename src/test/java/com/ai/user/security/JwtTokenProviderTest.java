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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JwtTokenProvider} 单元测试：role claim 签发/解析往返、旧令牌兼容(默认 USER)、
 * 非法令牌拒绝、以及"未配置密钥时随机兜底可用"。
 */
class JwtTokenProviderTest {

    /** 测试专用固定密钥(仅存在于测试内, 不是任何环境的真实凭据) */
    private static final String TEST_SECRET = "unit-test-only-secret-0123456789abcdef-32b";

    private final JwtTokenProvider provider = new JwtTokenProvider(propsWith(TEST_SECRET));

    private static AppProperties propsWith(String secret) {
        AppProperties props = new AppProperties();
        props.getAuth().setJwtSecret(secret);
        return props;
    }

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
        var key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
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

    /** 仓库不再提供默认密钥: 留空时构造函数生成一次性随机密钥, 签发的令牌仍可自解析 */
    @Test
    void blankSecretFallsBackToRandomKey() {
        JwtTokenProvider randomKeyProvider = new JwtTokenProvider(propsWith(""));

        String token = assertDoesNotThrow(() -> randomKeyProvider.createToken(9L, "carol", null));

        assertEquals("carol", randomKeyProvider.parse(token).username());
    }

    /** 两个未配置密钥的实例互不认账(随机密钥各自独立) —— 这正是"重启后旧 token 失效"的表现 */
    @Test
    void twoRandomKeyProvidersDoNotTrustEachOther() {
        String token = new JwtTokenProvider(propsWith("")).createToken(9L, "carol", null);

        assertThrows(BusinessException.class,
                () -> new JwtTokenProvider(propsWith("")).parse(token));
    }
}
