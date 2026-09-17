package com.ai.chat.dto;

/**
 * 对话响应。
 *
 * <p>来源不再返回前端(2026-09 契约变更): 引用来源只落库到 {@code chat_log.sources},
 * 前端拿不到——需要审计来源请查系统日志接口。
 */
public record ChatResponse(String content) {
}
