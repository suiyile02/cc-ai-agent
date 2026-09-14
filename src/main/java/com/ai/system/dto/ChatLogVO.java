package com.ai.system.dto;

import java.time.LocalDateTime;

/**
 * 对话日志 VO(系统管理模块)。
 */
public record ChatLogVO(
        Long id,
        String sessionId,
        Long userId,
        String userMessage,
        String assistantReply,
        String sources,
        String toolCalls,
        String modelName,
        Integer totalTokens,
        Integer durationMs,
        LocalDateTime createTime) {
}
