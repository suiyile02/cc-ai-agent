package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.rag.RagMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CachingIntentRouter} 单元测试：Redis 命中直接返回、未命中写缓存、
 * Redis 异常降级走真实路由、disabled 时完全旁路、evictAll 版本自增。
 *
 * <p>注意: currentVersion() 读版本键, 与缓存键读取共用 {@code valueOps.get}——
 * 测试须用"先通用 stub、后版本键精确 stub"的顺序, 让版本键命中 null(版本 0)。
 */
class CachingIntentRouterTest {

    private KeywordIntentRouter delegate;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AppProperties appProperties;
    private CachingIntentRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        delegate = mock(KeywordIntentRouter.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        appProperties = new AppProperties();
        router = new CachingIntentRouter(delegate, redis, appProperties);
    }

    /** 缓存路径基线: 通用 get 返回命中值, 版本键读取返回 null(版本 0, 精确 stub 覆盖通用 stub) */
    private void givenCacheRead(String cachedValue) {
        when(valueOps.get(anyString())).thenReturn(cachedValue);
        when(valueOps.get(CachingIntentRouter.VERSION_KEY)).thenReturn(null);
    }

    @Test
    void cacheHitReturnsWithoutCallingDelegate() {
        givenCacheRead(RagMode.TOOL.name());

        assertEquals(RagMode.TOOL, router.route("查询订单 A123 的物流状态"));
        verify(delegate, never()).route(anyString());
    }

    @Test
    void cacheMissCallsDelegateAndWritesCache() {
        givenCacheRead(null);
        when(delegate.route("年假有多少天")).thenReturn(RagMode.KB);

        assertEquals(RagMode.KB, router.route("年假有多少天"));
        verify(valueOps).set(anyString(), eq(RagMode.KB.name()),
                any(Duration.class));
    }

    @Test
    void redisErrorFallsBackToDelegate() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(delegate.route(anyString())).thenReturn(RagMode.GENERAL);

        assertEquals(RagMode.GENERAL, router.route("今天心情不错"));
        // 降级不抛异常、不影响主流程
    }

    @Test
    void disabledBypassesRedisEntirely() {
        appProperties.getIntentCache().setEnabled(false);
        when(delegate.route(anyString())).thenReturn(RagMode.KB);

        assertEquals(RagMode.KB, router.route("年假制度"));
        verify(redis, never()).opsForValue();
    }

    @Test
    void evictAllIncrementsVersionKey() {
        when(valueOps.increment(CachingIntentRouter.VERSION_KEY)).thenReturn(3L);

        assertEquals(3L, router.evictAll());
    }

    @Test
    void normalizationMakesWhitespaceVariantsShareCacheKey() {
        // 与语义缓存同口径: 去空白+小写 —— " 查询订单 " 与 "查询订单" 应命中同一键
        givenCacheRead(RagMode.TOOL.name());

        assertEquals(RagMode.TOOL, router.route("  查询 订单 A123 "));
    }
}
