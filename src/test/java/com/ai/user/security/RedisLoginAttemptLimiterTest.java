package com.ai.user.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisLoginAttemptLimiter} 的降级契约单测。
 *
 * <p>复现的线上缺陷：读侧(isLocked/remainingLockMs)原先未捕获 Redis 异常——Redis 不可达时
 * 连接异常会冒到 {@code GlobalExceptionHandler} 兜底，把"限流存储故障"放大成"登录接口 500"
 * (而写侧 recordFailure/recordSuccess 一直是 catch + WARN 放行，读写口径不一致)。
 * 本用例锁定读侧降级口径：异常一律吸收、按未锁定放行、打 WARN。
 */
class RedisLoginAttemptLimiterTest {

    private StringRedisTemplate redis;
    private RedisLoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        limiter = new RedisLoginAttemptLimiter(redis);
    }

    @Test
    void redisDownDegradesToNotLockedInsteadOfThrowing() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> limiter.isLocked("alice", "1.2.3.4"));
        assertFalse(limiter.isLocked("alice", "1.2.3.4"), "Redis 故障按未锁定放行");
    }

    @Test
    void lockKeyPresentStillLocks() {
        when(redis.hasKey("login:lock:u:alice")).thenReturn(true);

        assertTrue(limiter.isLocked("alice", "1.2.3.4"));
    }

    @Test
    void remainingLockMsIsZeroWhenRedisDown() {
        when(redis.getExpire(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> limiter.remainingLockMs("alice", "1.2.3.4"));
        assertEquals(0, limiter.remainingLockMs("alice", "1.2.3.4"));
    }

    @Test
    void remainingLockMsReadsTtlWhenAvailable() {
        when(redis.getExpire("login:lock:u:alice")).thenReturn(120L);
        when(redis.getExpire("login:lock:ip:1.2.3.4")).thenReturn(null);

        assertEquals(120_000, limiter.remainingLockMs("alice", "1.2.3.4"), "秒→毫秒, null 视为未锁");
    }

    @Test
    void recordFailureSwallowsRedisError() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> limiter.recordFailure("alice", "1.2.3.4"));
    }

    @Test
    void writeSideStillCountsFailuresAndLocksKey() {
        // 写侧语义回归(证明降级只改了读侧口径): 第 5 次失败写锁定键并删计数
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:fail:u:alice")).thenReturn(5L);

        limiter.recordFailure("alice", "1.2.3.4");

        verify(valueOps).set(eq("login:lock:u:alice"), eq("1"), any(java.time.Duration.class));
        verify(redis).delete("login:fail:u:alice");
    }
}
