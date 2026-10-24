package com.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对话请求体。sessionId 由创建会话接口下发。
 */
public record ChatRequest(
        @NotBlank(message = "sessionId 不能为空") String sessionId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 2000, message = "消息过长（最多 2000 字），请拆分后发送") String message) {
}
