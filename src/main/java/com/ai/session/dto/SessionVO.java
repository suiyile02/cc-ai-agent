package com.ai.session.dto;

import com.ai.session.entity.ChatSession.SessionType;

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
