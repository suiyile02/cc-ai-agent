package com.ai.user.security;

import com.ai.common.WarnThrottle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流器（Redis 实现，默认）：计数与锁定态存 Redis，多实例共享同一份计数
 * （进程内实现各实例独立，爆破者换实例即可绕过）。
 *
 * <p>键：{@code login:fail:{u|ip}:<标识>}（TTL=窗口 10min）、{@code login:lock:{u|ip}:<标识>}（TTL=锁定 5min）；
 * 计数达阈值写锁定键并清计数，登录成功删两维度全部键。
 *
 * <p>降级口径：读写两侧一律 catch 后**放行**并 WARN 节流。异常若外溢会被全局兜底成 500，
 * 即"Redis 挂 ⇒ 整站无法登录"；也不提供 fail-closed 开关（同样放大故障）。
 * 取舍与告警约定见 {@code docs/flow-map.md} §18 与 AGENTS.md「Redis 使用与降级约定」。
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

    /** 降级告警节流(Redis 故障时 60 秒一条) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

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
            degraded.warn("登录限流检查失败(已降级: 本次按未锁定放行): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 记录一次失败（用户名与 IP 两个维度各计一次）。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    @Override
    public void recordFailure(String username, String ip) {
        fail("u", username);
        fail("ip", ip);
    }

    /**
     * 登录成功：清除两个维度的计数与锁定标记。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
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
            degraded.warn("登录限流剩余时长查询失败(已降级: 按 0 处理): {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 单维度失败计数：首次设窗口 TTL，达阈值写锁定键并清计数。
     *
     * @param dim      维度标记（{@code u} 用户名 / {@code ip} 来源 IP）
     * @param identity 该维度的标识值（可空，空归入 unknown 桶）
     */
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
            degraded.warn("登录限流计数失败(已降级: 本次失败未被计数): {}", e.getMessage());
        }
    }

    /**
     * 删除单维度的计数键与锁定键。
     *
     * @param dim      维度标记（{@code u}/{@code ip}）
     * @param identity 该维度的标识值
     */
    private void del(String dim, String identity) {
        try {
            redis.delete("login:fail:" + dim + ":" + identity);
            redis.delete(lockKey(dim, identity));
        } catch (Exception e) {
            degraded.warn("登录限流清除失败(已降级: 旧计数键将随 TTL 过期): {}", e.getMessage());
        }
    }

    /** 键剩余 TTL 毫秒（Redis 无此键或无 TTL 时返回 0） */
    private long ttl(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 0 ? 0 : ttl * 1000L;
    }

    /** 锁定键名（标识为 null 时归入 unknown 桶，避免键名出现 "null" 歧义） */
    private String lockKey(String dim, String identity) {
        return "login:lock:" + dim + ":" + (identity == null ? "unknown" : identity);
    }
}
