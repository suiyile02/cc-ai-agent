package com.ai.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * RAG 检索调试请求(直接返回命中的知识块, 便于观察相似度阈值/TopK 效果)。
 */
public record RagDebugRequest(
        @NotBlank(message = "question 不能为空") String question,
        Integer topK,
        Double similarityThreshold) {

    public RagDebugRequest {
        if (topK == null || topK < 1) {
            topK = 5;
        }
        if (similarityThreshold == null) {
            similarityThreshold = 0.0;
        }
    }

    public record RagDebugResponse(String question, List<SourceVO> hits) {
    }
}
