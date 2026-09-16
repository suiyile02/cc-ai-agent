package com.ai.user.security;

import com.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 吊销黑名单(A1)：注销/禁用时把令牌 jti 写入 Redis, 拦截器校验命中即拒绝。
 * TTL = 令牌剩余有效期, 到期自动清除(令牌本身也已过期)。
 *
 * <p>降级策略: Redis 异常时按 {@code app.auth.blacklist-fail-open} 决定放行(默认, 保可用性)
 * 或拒绝(生产建议, 保安全)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "jwt:black:";

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;

    /**
     * 拉黑令牌(重复拉黑无害)。
     *
     * @param jti   令牌唯一 ID
     * @param ttlMs 剩余有效期毫秒(≤0 忽略)
     */
    public void ban(String jti, long ttlMs) {
        if (jti == null || jti.isBlank() || ttlMs <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMs));
            log.info("令牌已拉黑: jti={}, 剩余 {}ms", jti, ttlMs);
        } catch (Exception e) {
            log.warn("令牌拉黑失败(Redis 不可用?): {}", e.getMessage());
        }
    }

    /**
     * 检查令牌是否已被吊销。
     *
     * @param jti 令牌唯一 ID(可为 null——旧令牌无 jti, 直接放行)
     * @return true=已吊销, 应拒绝
     */
    public boolean isBanned(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            boolean failOpen = appProperties.getAuth().isBlacklistFailOpen();
            log.warn("令牌黑名单检查失败({}): {}", failOpen ? "放行" : "拒绝", e.getMessage());
            return !failOpen;
        }
    }
}
