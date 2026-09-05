package com.ai.dto;

import java.util.List;

/**
 * 对话响应。sources 为本次回答引用的知识库来源。
 */
public record ChatResponse(String content, List<SourceVO> sources) {
}
