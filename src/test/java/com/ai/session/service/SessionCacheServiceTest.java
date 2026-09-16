package com.ai.session.service;

import com.ai.session.entity.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionCacheService} 单元测试：正缓存读写、负缓存(穿透防护)与失效。
 * Redis 操作全部 mock, 不依赖真实 Redis。
 */
class SessionCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SessionCacheService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new SessionCacheService(redis, new ObjectMapper());
    }

    @Test
    void putNotFoundWritesAbsentMarkerWithShortTtl() {
        service.putNotFound("sid-absent");

        verify(valueOps).set(eq("session:absent:sid-absent"), eq("1"), any(Duration.class));
    }

    @Test
    void isNotFoundTrueWhenMarkerPresent() {
        when(redis.hasKey("session:absent:sid-absent")).thenReturn(true);

        assertTrue(service.isNotFound("sid-absent"));
    }

    @Test
    void isNotFoundFalseWhenMarkerAbsent() {
        when(redis.hasKey(anyString())).thenReturn(false);

        assertFalse(service.isNotFound("sid-absent"));
    }

    @Test
    void isNotFoundDegradesToFalseOnRedisError() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertFalse(service.isNotFound("sid-absent"), "Redis 异常按未命中处理, 回退 DB");
    }

    @Test
    void evictClearsMetaAndAbsentKeys() {
        service.evict("sid-1");

        verify(redis).delete(java.util.List.of("session:meta:sid-1", "session:absent:sid-1"));
    }

    @Test
    void putNotFoundFailureIsIgnored() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        service.putNotFound("sid-absent"); // 不抛异常
    }

    @Test
    void getReturnsNullOnRedisError() {
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertNull(service.get("sid-1"));
    }

    @Test
    void putAndGetRoundTrip() throws Exception {
        ChatSession session = new ChatSession();
        session.setSessionId("sid-1");
        session.setUserId(9L);
        String json = new ObjectMapper().writeValueAsString(session);
        when(valueOps.get("session:meta:sid-1")).thenReturn(json);

        service.put(session);
        verify(valueOps).set(eq("session:meta:sid-1"), eq(json), any(Duration.class));

        ChatSession loaded = service.get("sid-1");
        assertNotNull(loaded);
        assertEquals("sid-1", loaded.getSessionId());
        assertEquals(9L, loaded.getUserId());
    }
}