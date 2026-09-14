package com.ai.system.dto;

import java.time.LocalDateTime;

/**
 * 工具调用日志 VO(系统管理模块)。
 */
public record ToolCallLogVO(
        Long id,
        String sessionId, Long userId,
        String toolName,
        String inputParams,
        String outputResult,
        String status,
        Integer durationMs,
        String errorMessage,
        LocalDateTime createTime) {
}
