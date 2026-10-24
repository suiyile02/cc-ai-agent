package com.ai.chat.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChatRateLimiter} 契约测试: 两档窗口限额、开关与空 userId 旁路、Redis 故障 fail-open。
 *
 * <p>防刷量背景: 一条消息 = 4~7 次厂商调用, 并发限制只挡"同时"不挡高频串行——
 * 频率闸门是对话编排的第一道检查, 本组用例锁定其判定与降级口径。
 */
class ChatRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private AppProperties props;
    private ChatRateLimiter limiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        props = new AppProperties(); // 默认: enabled=true, perMinute=10, perDay=200
        limiter = new ChatRateLimiter(redis, props);
    }

    private void counts(long minute, long day) {
        when(ops.increment("chat:rate:min:7")).thenReturn(minute);
        when(ops.increment("chat:rate:day:7")).thenReturn(day);
    }

    @Test
    void underLimitPassesSilently() {
        counts(3L, 55L);
        assertDoesNotThrow(() -> limiter.check(7L));
    }

    @Test
    void minuteLimitExceededRejectsWith6011() {
        counts(11L, 55L); // 第 11 条 > 每分钟 10

        BusinessException e = assertThrows(BusinessException.class, () -> limiter.check(7L));
        assertEquals(ErrorCode.CHAT_RATE_LIMITED, e.getErrorCode());
        assertTrue(e.getMessage().contains("每分钟"), "拒绝信息应带分钟限额: " + e.getMessage());
    }

    @Test
    void dayLimitExceededRejectsEvenWhenMinuteFine() {
        counts(2L, 201L); // 分钟内没超, 但日额度已尽

        BusinessException e = assertThrows(BusinessException.class, () -> limiter.check(7L));
        assertEquals(ErrorCode.CHAT_RATE_LIMITED, e.getErrorCode());
        assertTrue(e.getMessage().contains("24 小时"), "拒绝信息应带日限额: " + e.getMessage());
    }

    @Test
    void disabledSwitchBypassesRedisEntirely() {
        props.getChat().getRateLimit().setEnabled(false);

        assertDoesNotThrow(() -> limiter.check(7L));
        verifyNoInteractions(redis);
    }

    @Test
    void nullUserIdSkipsCheck() {
        // 开发模拟头(X-User-Id)场景无用户概念, 不得因限流把调试路径打断
        assertDoesNotThrow(() -> limiter.check(null));
        verifyNoInteractions(redis);
    }

    @Test
    void redisFailureFailsOpenInsteadOfBlockingChat() {
        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> limiter.check(7L),
                "旁路保护不放大成对话不可用(与登录限流同哲学), 损失由 WARN 节流暴露");
    }

    @Test
    void firstHitSetsWindowExpiry() {
        counts(1L, 1L);
        limiter.check(7L);

        org.mockito.Mockito.verify(redis).expire("chat:rate:min:7", Duration.ofSeconds(60));
        org.mockito.Mockito.verify(redis).expire("chat:rate:day:7", Duration.ofHours(24));
    }
}
