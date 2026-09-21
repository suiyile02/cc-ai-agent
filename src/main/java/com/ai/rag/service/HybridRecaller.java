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
 * 多路召回：语义向量路 + 关键词 BM25 路。
 *
 * <p>语义路的相似度阈值**在本地过滤而非交给向量库**：向量库一旦先过滤, 低于阈值的分块就永不返回,
 * 决策日志里能看到的分数全部 ≥阈值, 分布被从底部截断——拿它定标必然得出"阈值还能再提高"的错误结论。
 * 本地过滤与原实现等价(过阈值者必然是排名最前的那批), 但额外得到"过滤前最大分"。
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
     * @param semantic        语义路命中（已过阈值, 按相似度降序）
     * @param keyword         关键词路命中（已按 BM25 降序）
     * @param semanticMaxScore 语义路**过滤前**的最大相似度; 该路无分数可得时为 0
     * @param vectorAvailable 向量库是否可用
     * @param keywordAvailable 关键词索引是否非空
     */
    record Recall(List<Document> semantic, List<Document> keyword, double semanticMaxScore,
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
        // 以 0 阈值召回: 让低于阈值的分数也能被观察到(定标依据), 阈值判定交给下面本地过滤
        List<Document> raw = vectorStore == null
                ? List.of() : semanticHits(vectorStore, query, topK);
        double semanticMaxScore = raw.stream().map(DocumentMeta::similarity)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        List<Document> semantic = raw.stream()
                // 分数不可得(null)时不作判定, 保留该候选——避免某个向量库实现不回填分数时整路清零
                .filter(d -> {
                    Double s = DocumentMeta.similarity(d);
                    return s == null || s >= threshold;
                })
                .toList();
        boolean hybrid = appProperties.getRag().isHybridEnabled();
        List<Document> keyword = hybrid && keywordAvailable
                ? keywordHits(query, Math.max(topK * 2, 10)) : List.of();
        return new Recall(semantic, keyword, semanticMaxScore, vectorStore != null, keywordAvailable);
    }

    /** 语义向量路（异常降级为空的该路结果）；阈值不在此处下发, 见 {@link #recall} */
    private List<Document> semanticHits(VectorStore store, String query, int topK) {
        try {
            return store.similaritySearch(SearchRequest.builder()
                    .query(query).topK(topK).similarityThreshold(0.0).build());
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
