package com.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 检索调试请求(返回命中的知识块与对话出口预测, 便于观察阈值/TopK/短查询扩展的效果)。
 *
 * <p>参数缺省口径<b>与对话一致</b>：{@code similarityThreshold} 留 null 即使用
 * {@code app.rag.similarity-threshold}(默认 0.45)。此前该字段留空会被归一成 0.0(不过滤),
 * 造成"调试页查得到、对话判无据"的第三种口径分叉——已取消该归一化。
 *
 * @param question            查询问题(必填)
 * @param topK                召回条数(缺省取 {@code app.rag.top-k}; 小于 1 同样回落配置值)
 * @param similarityThreshold 语义路余弦阈值(null=跟随对话配置)
 * @param expandShortQuery    是否允许短查询扩展(null=true, 与对话一致)
 */
public record RagDebugRequest(
        @NotBlank(message = "question 不能为空") String question,
        Integer topK,
        Double similarityThreshold,
        Boolean expandShortQuery) {

    /** 紧凑构造器：只归一非法 topK；阈值与扩展开关保持 null 以走"跟随对话配置"。 */
    public RagDebugRequest {
        if (topK != null && topK < 1) {
            topK = null;
        }
    }

    /**
     * 是否启用短查询扩展(缺省启用, 与对话同口径)。
     *
     * @return true=允许扩展
     */
    public boolean expandShort() {
        return expandShortQuery == null || expandShortQuery;
    }
}
