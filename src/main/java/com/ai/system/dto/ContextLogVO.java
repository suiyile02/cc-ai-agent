package com.ai.system.dto;

import java.time.LocalDateTime;

/**
 * 上下文装配可观测日志 VO。
 */
public record ContextLogVO(
        Long id,
        String sessionId,
        String userMessage,
        String rewrittenQuery,
        String intentMode,
        Integer systemTokens,
        Integer historyTokens,
        Integer summaryTokens,
        Integer ragTokens,
        Integer userTokens,
        Integer totalTokens,
        Integer modelMaxTokens,
        Integer historyMessages,
        Boolean truncated,
        Integer durationMs,
        LocalDateTime createdAt) {
}
