package com.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * RAG 检索调试请求(直接返回命中的知识块, 便于观察相似度阈值/TopK 效果)。
 *
 * @param question           查询问题(必填)
 * @param topK               召回条数(缺省 5, 小于 1 重置为 5)
 * @param similarityThreshold 相似度阈值(缺省 0, 即不做过滤)
 */
public record RagDebugRequest(
        @NotBlank(message = "question 不能为空") String question,
        Integer topK,
        Double similarityThreshold) {

    /**
     * 紧凑构造器：对可空/非法参数做默认值归一化。
     */
    public RagDebugRequest {
        if (topK == null || topK < 1) {
            topK = 5;
        }
        if (similarityThreshold == null) {
            similarityThreshold = 0.0;
        }
    }

    /**
     * RAG 检索调试响应。
     *
     * @param question 查询问题
     * @param hits     命中的知识块来源列表
     */
    /**
     * 【未被引用】调试接口直接返回 List&lt;SourceVO&gt;, 此嵌套 record 无构造/引用点, 可删除。
     */
    public record RagDebugResponse(String question, List<SourceVO> hits) {
    }
}
