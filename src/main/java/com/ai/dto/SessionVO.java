package com.ai.dto;

import com.ai.entity.ChatSession.SessionType;

import java.time.LocalDateTime;

/**
 * 会话 VO。
 */
public record SessionVO(
        Long id,
        String sessionId,
        String title,
        SessionType sessionType,
        Integer status,
        Long userId,
        LocalDateTime createTime) {
}
