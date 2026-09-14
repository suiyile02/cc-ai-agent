package com.ai.user.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginAttemptLimiter} 单元测试：失败累计/锁定/窗口重置/成功清零/双维度。
 */
class LoginAttemptLimiterTest {

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter();

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
}
