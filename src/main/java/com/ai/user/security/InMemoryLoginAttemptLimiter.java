package com.ai.user.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流器(进程内实现)：按"用户名"与"来源 IP"双维度计数, 连续失败达到阈值后锁定。
 *
 * <p>【已被替代-可选回退】默认实现已切换为 {@link RedisLoginAttemptLimiter}(多实例统一计数);
 * 本实现通过 {@code app.auth.rate-limit-backend=memory} 启用(单实例/测试场景)。
 *
 * <p>防暴力破解的最低保障(此前登录接口无任何失败限制, 可无限尝试密码)。
 * 采用滑动窗口: 每条记录保存"失败次数 + 窗口起始时间", 成功登录即清零;
 * 锁定期内直接拒绝(不查库不比对密码, 避免被当作探测口令的旁路)。
 *
 * <p>实现为进程内 Map——单实例有效; 多实例部署时应替换为 Redis 计数(见优化路线 P3)。
 * 条目带最后访问时间, 由 {@link #evictStale(long)} 清理防内存增长: 触发点在失败记录路径,
 * 且仅在条目数超过 {@link #EVICT_THRESHOLD} 时才扫(否则每个新失败用户名/IP 都会永久驻留)。
 */
@Component
@ConditionalOnProperty(
        name = "app.auth.rate-limit-backend", havingValue = "memory")
public class InMemoryLoginAttemptLimiter implements LoginAttemptLimiter {

    /**
     * 过期清理阈值：条目数超过该值才在失败记录路径顺带扫一次。
     * 门控是必要的——不设阈值则每次失败都线性扫描, 撞库(大量不同用户名)时退化成 O(n²) 自伤。
     */
    static final int EVICT_THRESHOLD = 1000;

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

    /** key = username 或 ip:&lt;ip&gt; */
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 判定是否被锁定(用户名或 IP 任一维度命中即锁定)。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return true=锁定期内, 应直接拒绝登录
     */
    @Override
    public boolean isLocked(String username, String ip) {
        long now = System.currentTimeMillis();
        return locked(username, now) || locked(ipKey(ip), now);
    }

    /**
     * 记录一次失败(两维度各计一次), 达阈值进入锁定; 条目过多时顺带清理过期条目。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    @Override
    public void recordFailure(String username, String ip) {
        recordFailure(username, ip, System.currentTimeMillis());
    }

    /**
     * 记录一次失败(指定时钟重载)。
     *
     * <p>【仅测试引用】生产路径走 {@link #recordFailure(String, String)}; 本重载供单测构造
     * "已过期的历史条目"以验证阈值门控的清理确实被失败记录路径触发(否则无法在不等待 15 分钟的情况下复现)。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @param now      当前毫秒时间戳
     */
    void recordFailure(String username, String ip, long now) {
        if (attempts.size() > EVICT_THRESHOLD) {
            evictStale(now);
        }
        fail(username, now);
        fail(ipKey(ip), now);
    }

    /**
     * 登录成功: 清除该用户名与该 IP 的失败计数。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    @Override
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
    @Override
    public long remainingLockMs(String username, String ip) {
        long now = System.currentTimeMillis();
        return Math.max(remaining(username, now), remaining(ipKey(ip), now));
    }

    /**
     * 清理过期条目(窗口与锁均已过期的), 防止 Map 无限增长;
     * 由失败记录路径在条目数超过 {@link #EVICT_THRESHOLD} 时触发。
     *
     * @param now 当前毫秒时间戳
     * @return 清理后的条目数(诊断用)
     */
    int evictStale(long now) {
        attempts.entrySet().removeIf(e ->
                now - Math.max(e.getValue().windowStartMs, e.getValue().lockedUntilMs) > WINDOW_MS + LOCK_MS);
        return attempts.size();
    }

    /**
     * 当前跟踪的条目数。
     *
     * <p>【仅测试引用】用于断言"阈值门控的过期清理"确实生效(内存增长防护无法从登录行为反推:
     * 过期条目在 {@link #fail(String, long)} 里会被滑动窗口重置, 语义上无害, 但会一直占着 Map)。
     *
     * @return Map 中的条目数
     */
    int trackedEntries() {
        return attempts.size();
    }

    /** 该键是否处于锁定期 */
    private boolean locked(String key, long now) {
        return remaining(key, now) > 0;
    }

    /** 该键剩余锁定毫秒数（无记录或未锁定为 0） */
    private long remaining(String key, long now) {
        Attempt a = attempts.get(key);
        return a == null ? 0 : Math.max(0, a.lockedUntilMs - now);
    }

    /**
     * 单维度失败累计：窗口过期则重开计数，达阈值转锁定并清零计数（解锁后重新累计）。
     *
     * @param key 维度键（用户名原值或 {@link #ipKey(String)}）
     * @param now 当前毫秒时间戳
     */
    private void fail(String key, long now) {
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.windowStartMs > WINDOW_MS) {
                a = new Attempt(now);
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntilMs = now + LOCK_MS;
                a.failures = 0;
            }
            return a;
        });
    }

    /** IP 维度键（null 归入 unknown 桶，与用户名维度区隔，防键相撞） */
    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
