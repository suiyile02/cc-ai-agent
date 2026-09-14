package com.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 对话请求体。sessionId 由创建会话接口下发。
 */
public record ChatRequest(
        @NotBlank(message = "sessionId 不能为空") String sessionId,
        @NotBlank(message = "message 不能为空") String message) {
}
