package com.ai.dto;

import com.ai.entity.ChatSession.SessionType;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求。sessionType: RAG / AGENT / HYBRID(缺省 HYBRID)
 */
public record SessionCreateRequest(
        @Size(max = 200, message = "标题长度不能超过 200") String title,
        SessionType sessionType) {

    public SessionType effectiveType() {
        return sessionType == null ? SessionType.HYBRID : sessionType;
    }
}
