package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SemanticAnswerCache} 单元测试：归一化/摘要、版本键失效、降级安全。
 * Redis 操作全部 mock, 不依赖真实 Redis。
 */
class SemanticAnswerCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AppProperties appProperties;
    private ChatClientProvider chatClientProvider;
    private SemanticAnswerCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        appProperties = new AppProperties();
        chatClientProvider = mock(ChatClientProvider.class);
        lenient().when(chatClientProvider.modelLabel()).thenReturn("qwen-test");
        cache = new SemanticAnswerCache(redis, new ObjectMapper(), appProperties, chatClientProvider);
    }

    private void givenVersion(String version) {
        lenient().when(valueOps.get("rag:kb:version")).thenReturn(version);
    }

    @Test
    void normalizeRemovesWhitespaceAndLowercases() {
        assertEquals("你好,世界", SemanticAnswerCache.normalize("  你好, 世界！ ".toLowerCase().replace("！", "")));
        assertEquals("abc123", SemanticAnswerCache.normalize("A BC\n\t123"));
        assertEquals("", SemanticAnswerCache.normalize(null));
    }

    @Test
    void getMissesWhenKeyAbsent() {
        givenVersion("0");
        when(valueOps.get(anyString())).thenReturn(null);

        assertNull(cache.get("年假有几天"));
    }

    @Test
    void putThenGetRoundTrip() throws Exception {
        givenVersion("3");
        // 写入: 应带版本号键 + TTL
        cache.put("年假有几天", "10 天", List.of("考勤与假期制度"));
        verify(valueOps).set(contains("v3:"), anyString(),
                eq(java.time.Duration.ofHours(appProperties.getSemanticCache().getTtlHours())));

        // 读取: 同版本下命中
        String json = new ObjectMapper().writeValueAsString(
                new SemanticAnswerCache.CachedAnswer("10 天", List.of("考勤与假期制度")));
        when(valueOps.get(contains("v3:"))).thenReturn(json);

        SemanticAnswerCache.CachedAnswer answer = cache.get("年假有几天");
        assertEquals("10 天", answer.content());
        assertEquals(List.of("考勤与假期制度"), answer.sources());
    }

    @Test
    void versionBumpInvalidatesOldEntries() {
        givenVersion("4"); // 知识库已变更(v4), 缓存写于 v3
        when(valueOps.get(contains("v3:"))).thenReturn("{\"content\":\"旧答案\",\"sources\":[]}");

        assertNull(cache.get("年假有几天"));
    }

    /**
     * 键带对话模型维度: 写入的键形如 {@code rag:answer:v3:m{模型}:{摘要}}, 且切换模型后
     * 旧模型产出的回答不再被命中(答案风格/质量随模型变化, 与知识库版本无关, 只能靠键隔离)。
     */
    @Test
    void answerKeyIncludesModelAndModelSwitchInvalidates() throws Exception {
        givenVersion("3");
        cache.put("年假有几天", "10 天", List.of("考勤与假期制度"));
        verify(valueOps).set(eq("rag:answer:v3:mqwen-test:"
                + SemanticAnswerCache.digest(SemanticAnswerCache.normalize("年假有几天"))),
                anyString(), any(java.time.Duration.class));

        // 同一问题在旧模型(qwen-test)下的缓存, 换模型后读不到
        String json = new ObjectMapper().writeValueAsString(
                new SemanticAnswerCache.CachedAnswer("旧模型答案", List.of()));
        when(valueOps.get(contains(":mqwen-test:"))).thenReturn(json);
        when(chatClientProvider.modelLabel()).thenReturn("qwen-new");

        assertNull(cache.get("年假有几天"), "切换模型后不应命中旧模型的回答");
        verify(valueOps).get(contains(":mqwen-new:"));
    }

    /** 模型名中的非法键字符被替换, 保证 Redis 键可 grep 且不被分隔符破坏 */
    @Test
    void modelTagSanitizesKeySeparators() {
        givenVersion("1");
        when(chatClientProvider.modelLabel()).thenReturn("gpt 4:o/lab");
        cache.put("任意", "答案", List.of());

        verify(valueOps).set(startsWith("rag:answer:v1:mgpt_4_o_lab:"), anyString(),
                any(java.time.Duration.class));
    }

    @Test
    void evictAllIncrementsVersion() {
        givenVersion("5");
        cache.evictAll();
        verify(valueOps).increment("rag:kb:version");
    }

    @Test
    void disabledCacheNeverTouchesRedis() {
        appProperties.getSemanticCache().setEnabled(false);

        assertNull(cache.get("任意问题"));
        cache.put("任意问题", "答案", List.of());
        cache.evictAll();
        verify(valueOps, never()).get(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void redisFailureDegradesToMiss() {
        givenVersion("0");
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertNull(cache.get("年假有几天"));
    }

    @Test
    void putMissWritesVersionedMissKeyWithShortTtl() {
        givenVersion("3");

        cache.putMiss("年假有几天");

        verify(valueOps).set(contains("rag:miss:v3:"), eq("1"),
                eq(java.time.Duration.ofMinutes(appProperties.getSemanticCache().getMissTtlMinutes())));
    }

    @Test
    void isMissTrueWhenMarkerPresent() {
        givenVersion("3");
        when(redis.hasKey(contains("rag:miss:v3:"))).thenReturn(true);

        assertTrue(cache.isMiss("年假有几天"));
    }

    @Test
    void isMissFalseWhenMarkerAbsent() {
        givenVersion("3");
        when(redis.hasKey(anyString())).thenReturn(false);

        assertFalse(cache.isMiss("年假有几天"));
    }

    @Test
    void versionBumpInvalidatesMissMarkers() {
        givenVersion("4"); // 知识库已变更(v4), 负缓存写于 v3
        when(redis.hasKey(contains("v3:"))).thenReturn(true);
        when(redis.hasKey(contains("v4:"))).thenReturn(false);

        assertFalse(cache.isMiss("年假有几天"), "旧版本负缓存键不应被命中");
    }

    @Test
    void disabledCacheSkipsMissOps() {
        appProperties.getSemanticCache().setEnabled(false);

        assertFalse(cache.isMiss("任意问题"));
        cache.putMiss("任意问题");
        verify(redis, never()).hasKey(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void redisFailureDegradesMissCheckToFalse() {
        givenVersion("0");
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertFalse(cache.isMiss("年假有几天"));
    }
}
