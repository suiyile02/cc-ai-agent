package com.ai.knowledge.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IngestionFailureMessages} 友好文案映射单测: 解析/乱码/嵌入失败的兜底必须为中文,
 * 不得把原始英文异常(如 DashScope 400 batch 超限)透出给前端文档列表。
 */
class IngestionFailureMessagesTest {

    private static final String EMBEDDING_FALLBACK = "文档向量化失败";

    @Test
    void parseFailureGetsFriendlyText() {
        assertEquals("文件内容为空或无法解析出有效文本(可能为纯图片/音视频/扫描件, 或格式不受支持)",
                IngestionFailureMessages.of(new IllegalStateException("未能从文档中解析出有效文本")),
                "解析不出文本必须给友好提示");
    }

    @Test
    void garbledTextGetsFriendlyText() {
        assertEquals("文档内容疑似乱码(编码异常)，无法入库",
                IngestionFailureMessages.of(new IllegalStateException("文档内容疑似乱码(替换符占比 12%)，无法入库")));
    }

    @Test
    void embeddingBatchLimit400IsMappedToChineseNotRawEnglish() {
        // 生产实测: DashScope 兼容接口拒"单次>20 条", Spring AI 抛 BadRequestException
        String raw = "com.openai.errors.BadRequestException: 400: <400> InternalError.Algo.InvalidParameter: "
                + "Value error, batch size is invalid, it should not be larger than 20.: input.contents";
        String mapped = IngestionFailureMessages.of(new RuntimeException(raw));

        assertTrue(mapped.contains(EMBEDDING_FALLBACK), "嵌入批量超限必须给中文兜底: " + mapped);
        assertTrue(!mapped.contains("BadRequestException"), "不得把原始英文异常写进 error_message");
        assertTrue(!mapped.contains("batch size"), "不得把原始英文异常写进 error_message");
    }

    @Test
    void embeddingRateLimitAndInvalidApiKeyAreMapped() {
        assertTrue(IngestionFailureMessages.of(new RuntimeException("429 rate limit exceeded"))
                .contains(EMBEDDING_FALLBACK));
        assertTrue(IngestionFailureMessages.of(new RuntimeException("401 Invalid API key"))
                .contains(EMBEDDING_FALLBACK));
        assertTrue(IngestionFailureMessages.of(new RuntimeException("Insufficient balance"))
                .contains(EMBEDDING_FALLBACK));
    }

    @Test
    void unrelatedExceptionStillTruncatedNotMapped() {
        String raw = "NPE somewhere in my code base with a long message";
        assertEquals(raw, IngestionFailureMessages.of(new RuntimeException(raw)),
                "无关异常保持原样(截断), 不得误命中嵌入兜底");
    }

    @Test
    void nullMessageFallsBackToGeneric() {
        assertEquals("入库失败", IngestionFailureMessages.of(new RuntimeException()));
    }
}
