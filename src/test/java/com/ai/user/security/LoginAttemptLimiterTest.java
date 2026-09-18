package com.ai.user.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryLoginAttemptLimiter} 单元测试：失败累计/锁定/窗口重置/成功清零/双维度/条目清理。
 * (Redis 实现语义等价, 其键与 TTL 口径、降级契约见 {@link RedisLoginAttemptLimiterTest}。)
 */
class LoginAttemptLimiterTest {

    private final InMemoryLoginAttemptLimiter limiter = new InMemoryLoginAttemptLimiter();

    @Test
    void noLockBeforeThreshold() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("alice", "1.2.3.4");
        }
        assertFalse(limiter.isLocked("alice", "1.2.3.4"));
    }

    @Test
    void locksAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "1.2.3.4");
        }
        assertTrue(limiter.isLocked("alice", "1.2.3.4"));
        assertTrue(limiter.remainingLockMs("alice", "1.2.3.4") > 0);
    }

    @Test
    void successClearsFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("bob", "2.3.4.5");
        }
        limiter.recordSuccess("bob", "2.3.4.5");

        // 清零后再失败 4 次也不会锁(需要重新累计 5 次)
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("bob", "2.3.4.5");
        }
        assertFalse(limiter.isLocked("bob", "2.3.4.5"));
    }

    @Test
    void ipDimensionLocksEvenWithDifferentUsernames() {
        // 同一 IP 换 5 个用户名爆破, IP 维度应触发锁定
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("user" + i, "3.3.3.3");
        }
        assertTrue(limiter.isLocked("another-user", "3.3.3.3"));
    }

    @Test
    void usernameDimensionLocksEvenFromDifferentIps() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("victim", "10.0.0." + i);
        }
        assertTrue(limiter.isLocked("victim", "11.0.0.1"));
    }

    @Test
    void evictStaleRemovesOnlyExpiredEntries() {
        limiter.recordFailure("carol", "4.4.4.4");
        // 窗口(10min) + 锁定(5min) 之后的时刻, 条目应被清理
        long future = System.currentTimeMillis() + 16 * 60_000L;
        limiter.evictStale(future);

        assertFalse(limiter.isLocked("carol", "4.4.4.4"));
    }

    @Test
    void nullIpIsTreatedAsUnknownBucket() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("dave", null);
        }
        assertTrue(limiter.isLocked("dave", null));
    }

    /**
     * 内存泄漏防护: 条目数越过阈值后, 失败记录路径必须清掉过期条目。
     *
     * <p>缺陷原状: {@code evictStale} 无任何生产调用方, 每个出现过的用户名/IP 永久驻留。
     * 用"25 分钟前"的失败构造已过期条目(窗口 10min + 锁定 5min 均已过)。
     */
    @Test
    void failurePathEvictsStaleEntriesOnceOverThreshold() {
        long now = System.currentTimeMillis();
        long stale = now - 25 * 60_000L;
        for (int i = 0; i <= InMemoryLoginAttemptLimiter.EVICT_THRESHOLD; i++) {
            limiter.recordFailure("attacker" + i, null, stale);
        }
        assertTrue(limiter.trackedEntries() > InMemoryLoginAttemptLimiter.EVICT_THRESHOLD,
                "前置条件: 已越过清理阈值");

        limiter.recordFailure("fresh-user", null, now);

        assertEquals(2, limiter.trackedEntries(), "过期条目应被本轮失败记录清理, 只剩新用户名+IP 两条");
    }

    /** 阈值门控: 条目很少时不做线性扫描(否则撞库场景每次失败都全表扫, 退化为 O(n²)) */
    @Test
    void noEvictionWhileBelowThreshold() {
        long now = System.currentTimeMillis();
        long stale = now - 25 * 60_000L;
        limiter.recordFailure("eve", null, stale);
        int before = limiter.trackedEntries();

        limiter.recordFailure("frank", null, now);

        assertEquals(before + 1, limiter.trackedEntries(), "未越阈则不清理, 过期条目仍在");
    }
}
