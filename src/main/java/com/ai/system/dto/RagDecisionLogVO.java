package com.ai.system.dto;

import java.time.LocalDateTime;

/**
 * RAG 意图路由决策日志 VO。
 */
public record RagDecisionLogVO(
        Long id,
        String sessionId,
        String userMessage,
        String ragMode,
        String answerOutcome,
        String sessionType,
        Boolean retrievalExecuted,
        Integer semanticHits,
        Integer keywordHits,
        Integer finalHits,
        Integer topK,
        Double similarityThreshold,
        Double semanticMaxScore,
        String rerankMode,
        Integer durationMs,
        LocalDateTime createdAt) {
}
