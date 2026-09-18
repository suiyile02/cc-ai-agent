package com.ai.user.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流器(Redis 实现, 默认)：计数与锁定状态存 Redis,
 * 多实例部署下全局统一(进程内实现的各实例计数独立, 爆破者可换实例绕过)。
 *
 * <p>键设计:
 * <ul>
 *   <li>{@code login:fail:u:<username>} / {@code login:fail:ip:<ip>} —— 失败计数, TTL=失败窗口(10 分钟);</li>
 *   <li>{@code login:lock:u:<username>} / {@code login:lock:ip:<ip>} —— 锁定标记, TTL=锁定时长(5 分钟)。</li>
 * </ul>
 * 计数达阈值写入锁定键并清零计数; 登录成功删除两维度全部键。
 *
 * <p>降级口径(读写一致): Redis 不可用时**一律放行**——写侧 {@link #recordFailure(String, String)}
 * 与读侧 {@link #isLocked(String, String)}/{@link #remainingLockMs(String, String)}
 * 均捕获异常并打 WARN, 绝不把限流故障变成登录 500(Redis 连接异常若不在此吸收,
 * 会冒到 {@code GlobalExceptionHandler} 兜底 → 5001/HTTP 500, 即"Redis 挂 ⇒ 登录全挂")。
 * 代价是 Redis 故障期间限流暂停(无防护窗口), 由 WARN 日志暴露给运维;
 * 与令牌黑名单的 {@code app.auth.blacklist-fail-open} 相比, 此处不设为可拒绝——
 * 拒绝会把基础设施故障放大成整站无法登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.rate-limit-backend", havingValue = "redis", matchIfMissing = true)
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Duration LOCK = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    /**
     * 查询锁定键(用户名 + IP 双维度, 任一命中即锁定)。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return true=锁定期内; Redis 异常按未锁定处理(放行登录, 见类 javadoc)
     */
    @Override
    public boolean isLocked(String username, String ip) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(lockKey("u", username)))
                    || Boolean.TRUE.equals(redis.hasKey(lockKey("ip", ip)));
        } catch (Exception e) {
            log.warn("登录限流检查失败, 本次按未锁定放行(Redis 不可用?): {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void recordFailure(String username, String ip) {
        fail("u", username);
        fail("ip", ip);
    }

    @Override
    public void recordSuccess(String username, String ip) {
        del("u", username);
        del("ip", ip);
    }

    /**
     * 剩余锁定毫秒数(取两维度较大值), 供响应文案。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return 剩余锁定毫秒; 未锁定或 Redis 异常返回 0
     */
    @Override
    public long remainingLockMs(String username, String ip) {
        try {
            return Math.max(ttl(lockKey("u", username)), ttl(lockKey("ip", ip)));
        } catch (Exception e) {
            log.warn("登录限流剩余时长查询失败(按 0 处理): {}", e.getMessage());
            return 0;
        }
    }

    private void fail(String dim, String identity) {
        String counter = "login:fail:" + dim + ":" + identity;
        try {
            Long count = redis.opsForValue().increment(counter);
            if (count != null && count == 1) {
                redis.expire(counter, WINDOW);
            }
            if (count != null && count >= MAX_FAILURES) {
                redis.opsForValue().set(lockKey(dim, identity), "1", LOCK);
                redis.delete(counter);
            }
        } catch (Exception e) {
            // Redis 不可用时降级放行(不阻塞登录主流程), 由告警暴露
            log.warn("登录限流计数失败(Redis 不可用?): {}", e.getMessage());
        }
    }

    private void del(String dim, String identity) {
        try {
            redis.delete("login:fail:" + dim + ":" + identity);
            redis.delete(lockKey(dim, identity));
        } catch (Exception e) {
            log.warn("登录限流清除失败(Redis 不可用?): {}", e.getMessage());
        }
    }

    private long ttl(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 0 ? 0 : ttl * 1000L;
    }

    private String lockKey(String dim, String identity) {
        return "login:lock:" + dim + ":" + (identity == null ? "unknown" : identity);
    }
}
