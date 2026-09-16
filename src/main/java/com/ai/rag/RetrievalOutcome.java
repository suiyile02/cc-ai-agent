package com.ai.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 一次混合检索的完整结果(供意图路由决策落库审计与上下文注入)。
 *
 * @param hits          最终注入上下文的命中文档(已重排、取 Top-K)
 * @param executed      是否真的执行了检索(存在可用向量库或关键词索引)
 * @param semanticCount 语义向量路召回数量
 * @param keywordCount  关键词 BM25 路召回数量
 * @param degraded      检索是否因超时/异常而降级(降级时的"零命中"不可作为"无答案"依据)
 */
public record RetrievalOutcome(List<Document> hits, boolean executed,
                               int semanticCount, int keywordCount, boolean degraded) {

    /** 无任何可用召回源/未检索时的空结果 */
    public static RetrievalOutcome none() {
        return new RetrievalOutcome(List.of(), false, 0, 0, false);
    }

    /**
     * 检索因超时/异常降级的空结果。
     *
     * <p>与 {@link #none()} 的区别在于 {@code executed=true}: 审计不变式
     * "rag_mode=KB 且 retrieval_executed=false ⟺ 语义缓存命中" 依赖该字段,
     * 故降级必须记为已执行, 否则会被误判成缓存命中。
     * {@code degraded=true} 表示零命中是"检索失败"而非"确实无答案", 语义负缓存据此跳过写入。
     *
     * @return 已执行但因超时/异常降级的空结果
     */
    public static RetrievalOutcome executedEmpty() {
        return new RetrievalOutcome(List.of(), true, 0, 0, true);
    }
}