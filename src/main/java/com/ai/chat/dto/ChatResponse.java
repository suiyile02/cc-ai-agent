package com.ai.chat.dto;

import java.util.List;

/**
 * 对话响应。sources 为本次回答引用的来源文档名列表(去重)。
 */
public record ChatResponse(String content, List<String> sources) {
}
