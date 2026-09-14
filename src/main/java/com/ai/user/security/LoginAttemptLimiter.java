package com.ai.user.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流器(进程内)：按"用户名"与"来源 IP"双维度计数, 连续失败达到阈值后锁定。
 *
 * <p>防暴力破解的最低保障(此前登录接口无任何失败限制, 可无限尝试密码)。
 * 采用滑动窗口: 每条记录保存"失败次数 + 窗口起始时间", 成功登录即清零;
 * 锁定期内直接拒绝(不查库不比对密码, 避免被当作探测口令的旁路)。
 *
 * <p>实现为进程内 Map——单实例有效; 多实例部署时应替换为 Redis 计数(见优化路线 P3)。
 * 条目带最后访问时间, 由 {@link #evictStale(long)} 定期清理防内存增长。
 */
@Component
public class LoginAttemptLimiter {

    /** 失败记录条目 */
    static final class Attempt {
        int failures;
        long windowStartMs;
        long lockedUntilMs;

        Attempt(long now) {
            this.windowStartMs = now;
        }
    }

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MS = 10 * 60_000L;      // 失败计数窗口 10 分钟
    private static final long LOCK_MS = 5 * 60_000L;         // 锁定 5 分钟

    /** key = username 或 ip:<ip> */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 判定是否被锁定(用户名或 IP 任一维度命中即锁定)。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return true=锁定期内, 应直接拒绝登录
     */
    public boolean isLocked(String username, String ip) {
        long now = System.currentTimeMillis();
        return locked(username, now) || locked(ipKey(ip), now);
    }

    /**
     * 记录一次失败(两维度各计一次), 达阈值进入锁定。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    public void recordFailure(String username, String ip) {
        long now = System.currentTimeMillis();
        fail(username, now);
        fail(ipKey(ip), now);
    }

    /**
     * 登录成功: 清除该用户名与该 IP 的失败计数。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    public void recordSuccess(String username, String ip) {
        attempts.remove(username);
        attempts.remove(ipKey(ip));
    }

    /**
     * 剩余锁定毫秒数(供日志/响应提示), 未锁定返回 0。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return 剩余锁定毫秒
     */
    public long remainingLockMs(String username, String ip) {
        long now = System.currentTimeMillis();
        return Math.max(remaining(username, now), remaining(ipKey(ip), now));
    }

    /**
     * 清理过期条目(窗口与锁均已过期的), 防止 Map 无限增长; 由登录路径顺带触发。
     *
     * @param now 当前毫秒时间戳
     * @return 清理后的条目数(诊断用)
     */
    int evictStale(long now) {
        attempts.entrySet().removeIf(e ->
                now - Math.max(e.getValue().windowStartMs, e.getValue().lockedUntilMs) > WINDOW_MS + LOCK_MS);
        return attempts.size();
    }

    private boolean locked(String key, long now) {
        return remaining(key, now) > 0;
    }

    private long remaining(String key, long now) {
        Attempt a = attempts.get(key);
        return a == null ? 0 : Math.max(0, a.lockedUntilMs - now);
    }

    private void fail(String key, long now) {
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStartMs > WINDOW_MS) {
                a = new Attempt(now);
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntilMs = now + LOCK_MS;
                a.failures = 0; // 锁定后重置计数, 解锁后重新累计
            }
            return a;
        });
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
