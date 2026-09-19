package com.ai.rag.service;

import com.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多路召回：语义向量路（阈值由向量库过滤）+ 关键词 BM25 路。
 *
 * <p>单一路失败只丢那一路（WARN 后返回空列表），不影响另一路与整体对话可用性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class HybridRecaller {

    /**
     * 两路召回结果与可用性标记。
     *
     * @param semantic        语义路命中（已按相似度降序）
     * @param keyword         关键词路命中（已按 BM25 降序）
     * @param vectorAvailable 向量库是否可用
     * @param keywordAvailable 关键词索引是否非空
     */
    record Recall(List<Document> semantic, List<Document> keyword,
                  boolean vectorAvailable, boolean keywordAvailable) {

        /** 两路都不可用（未配置 Embedding 且未入库） */
        boolean nothingUsable() {
            return !vectorAvailable && !keywordAvailable;
        }
    }

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final KeywordIndex keywordIndex;
    private final AppProperties appProperties;

    /**
     * 是否具备任一路检索能力（向量库存在或关键词索引非空）。
     *
     * @return true=可检索
     */
    boolean available() {
        return vectorStoreProvider.getIfAvailable() != null || !keywordIndex.isEmpty();
    }

    /**
     * 执行两路召回。是否启用关键词路由 {@code app.rag.hybrid-enabled} 决定；
     * 关键词召回宽度取 {@code max(topK*2, 10)}，把收敛交给后续重排。
     *
     * @param query     检索问题
     * @param topK      最终注入条数（用于推算关键词路召回宽度）
     * @param threshold 语义路相似度阈值
     * @return 两路结果与可用性标记
     */
    Recall recall(String query, int topK, double threshold) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        boolean keywordAvailable = !keywordIndex.isEmpty();
        List<Document> semantic = vectorStore == null
                ? List.of() : semanticHits(vectorStore, query, topK, threshold);
        boolean hybrid = appProperties.getRag().isHybridEnabled();
        List<Document> keyword = hybrid && keywordAvailable
                ? keywordHits(query, Math.max(topK * 2, 10)) : List.of();
        return new Recall(semantic, keyword, vectorStore != null, keywordAvailable);
    }

    /** 语义向量路（异常降级为空的该路结果） */
    private List<Document> semanticHits(VectorStore store, String query, int topK, double threshold) {
        try {
            return store.similaritySearch(SearchRequest.builder()
                    .query(query).topK(topK).similarityThreshold(threshold).build());
        } catch (Exception e) {
            log.warn("语义向量检索失败, 忽略该路召回: {}", e.getMessage());
            return List.of();
        }
    }

    /** 关键词 BM25 路（异常降级为空的该路结果） */
    private List<Document> keywordHits(String query, int topK) {
        try {
            String collection = appProperties.getRag().getCollectionName();
            return keywordIndex.search(query, topK).stream()
                    .map(hit -> DocumentMeta.fromKeywordHit(hit, collection)).toList();
        } catch (Exception e) {
            log.warn("关键词检索失败, 忽略该路召回: {}", e.getMessage());
            return List.of();
        }
    }
}
