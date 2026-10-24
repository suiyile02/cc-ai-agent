package com.ai.chat.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.config.props.ChatProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 每用户对话频率限制(部署演示防刷量, 2026-10-13): Redis 固定窗口计数, 超限抛 6011/HTTP 429。
 *
 * <p>为什么需要: 一条消息 = 查询改写+短查询扩展+嵌入+重排api+对话补全等 <b>4~7 次厂商调用</b>,
 * 并发限制({@link ChatConcurrencyGuard})只挡"同时进行", 挡不住脚本一秒一条的高频串行刷量。
 *
 * <p>两档窗口: 每分钟(默认 10 条)与每日(默认 200 条, 自当日首条起 24h 滚动), 参数见
 * {@code app.chat.rate-limit.*}。语义软限制: 固定窗口非原子(先 INCR 后 EXPIRE),
 * 并发首条的窗口起点可能相差毫秒级——防刷量足够, 不追求精确配额。
 *
 * <p>降级口径与登录限流一致: Redis 异常一律放行并 WARN 节流({@link WarnThrottle})——
 * 旁路保护不放大成"对话不可用"; 计数误差/放行损失由 INFO 级命中日志与 Prometheus 指标暴露。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRateLimiter {

    private static final String MINUTE_KEY = "chat:rate:min:";
    private static final String DAY_KEY = "chat:rate:day:";

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;
    /** 降级告警节流: Redis 故障时每请求两处降级, 折成 60 秒一条 */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    /**
     * 频率检查(在对话编排最前调用, 未超限静默返回)。
     *
     * @param userId 当前登录用户 ID(null 时跳过——开发模拟头场景无用户概念)
     * @throws BusinessException CHAT_RATE_LIMITED(6011, HTTP 429) 超出分钟/日限额
     */
    public void check(Long userId) {
        ChatProps.RateLimit cfg = appProperties.getChat().getRateLimit();
        if (!cfg.isEnabled() || userId == null) {
            return;
        }
        try {
            long minute = incr(MINUTE_KEY + userId, Duration.ofSeconds(60));
            if (minute > cfg.getPerMinute()) {
                throw new BusinessException(ErrorCode.CHAT_RATE_LIMITED,
                        "操作过于频繁（每分钟最多 " + cfg.getPerMinute() + " 条），请稍后再试");
            }
            long day = incr(DAY_KEY + userId, Duration.ofHours(24));
            if (day > cfg.getPerDay()) {
                throw new BusinessException(ErrorCode.CHAT_RATE_LIMITED,
                        "今日对话额度已用完（每 24 小时最多 " + cfg.getPerDay() + " 条），明天再来吧");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // fail-open: Redis 故障不阻断对话(可见性靠 WARN 节流), 与登录限流同哲学
            degraded.warn("对话频率限制不可用(已降级: 本轮放行): {}", e.getMessage());
        }
    }

    /**
     * 窗口内自增(首条设置过期, 其余沿用)。
     *
     * @return 当前窗口内计数(含本次)
     */
    private long incr(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }
}
