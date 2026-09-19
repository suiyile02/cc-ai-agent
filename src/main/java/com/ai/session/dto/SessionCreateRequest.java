package com.ai.session.dto;

import com.ai.session.SessionType;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求。
 *
 * @param title       会话标题(≤200 字符, 可选)
 * @param sessionType 会话类型 RAG / AGENT / HYBRID(缺省 HYBRID)
 */
public record SessionCreateRequest(
        @Size(max = 200, message = "标题长度不能超过 200") String title,
        SessionType sessionType) {

    /**
     * 获取生效的会话类型(空值归一化为 HYBRID)。
     *
     * @return 非空 SessionType
     */
    public SessionType effectiveType() {
        return sessionType == null ? SessionType.HYBRID : sessionType;
    }
}
