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
 */
public record RetrievalOutcome(List<Document> hits, boolean executed,
                               int semanticCount, int keywordCount) {

    /** 无任何可用召回源/未检索时的空结果 */
    public static RetrievalOutcome none() {
        return new RetrievalOutcome(List.of(), false, 0, 0);
    }
}
