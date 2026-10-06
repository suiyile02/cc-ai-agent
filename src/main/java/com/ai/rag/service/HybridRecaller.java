package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.knowledge.DocumentVisibility;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.SourceVO;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 多路召回：语义向量路 + 关键词 BM25 路。
 *
 * <p><b>本类不做阈值过滤</b>(两路都按原样返回), 阈值判定统一放到重排之后, 由
 * {@link RagRetrievalService} 收口。两个理由:
 * <ol>
 *   <li><b>定标需要未截断的分数</b>: 一旦先按阈值过滤, 决策日志里能看到的分数全部 ≥阈值,
 *       分布被从底部截断——拿它定标必然得出"阈值还能再提高"的错误结论;</li>
 *   <li><b>重排必须看到全量候选</b>: 阈值是"字面/向量距离"判断, 排在第 6 位的低余弦分块完全可能是
 *       唯一真正回答问题的段落(短查询、跨词表说法时尤其如此)。先过滤再重排等于让它失去了被捞回来的机会,
 *       "调试页查得到、对话却答无据"的观感正来自这里。</li>
 * </ol>
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
     * @param semantic        语义路命中（**未经阈值过滤**, 按相似度降序）
     * @param keyword         关键词路命中（已按 BM25 降序）
     * @param semanticMaxScore 语义路的最大相似度; 该路无分数可得时为 0
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
    private final DocumentVisibility documentVisibility;
    private final AppProperties appProperties;

    /**
     * 执行两路召回。是否启用关键词路由 {@code app.rag.hybrid-enabled} 决定；
     * 关键词召回宽度取 {@code max(topK*2, 10)}，把收敛交给后续重排。
     *
     * <p>参数表里没有阈值: 召回阶段刻意与阈值无关(见类注释), 阈值判定在重排之后收口。
     * <b>禁用文档排除</b>(管理员开关, SSOT 在 {@code knowledge_document.enabled}): 语义路
     * 下推为 Qdrant 查询过滤(不占 Top-K 名额——后过滤会静默缩水有效 K), BM25 路召回后过滤
     * (过取宽度天然补偿)。两路给到 RRF 的候选集均不含禁用文档。
     *
     * @param query 检索问题
     * @param topK  最终注入条数（用于推算关键词路召回宽度）
     * @return 两路结果与可用性标记
     */
    Recall recall(String query, int topK) {
        Set<Long> disabled = documentVisibility.disabledDocIds();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        boolean keywordAvailable = !keywordIndex.isEmpty();
        // 以 0 阈值召回: 低于阈值的分数也要能被观察到(定标依据), 且重排要看全量候选
        List<Document> raw = vectorStore == null
                ? List.of() : semanticHits(vectorStore, query, topK, disabled);
        double semanticMaxScore = raw.stream().map(DocumentMeta::similarity)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        boolean hybrid = appProperties.getRag().isHybridEnabled();
        List<Document> keyword = hybrid && keywordAvailable
                ? keywordHits(query, Math.max(topK * 2, 10)).stream()
                        // doc_id 缺失的点不过滤(缺信息保守保留, 与分数不可得保留同哲学)
                        .filter(d -> {
                            Long docId = DocumentMeta.docId(d);
                            return docId == null || !disabled.contains(docId);
                        })
                        .toList()
                : List.of();
        return new Recall(raw, keyword, semanticMaxScore, vectorStore != null, keywordAvailable);
    }

    /** 语义向量路（异常降级为空的该路结果）；阈值不在此处下发, 见 {@link #recall}。
     *  禁用集非空时下推 Qdrant 查询过滤: payload 中 doc_id 为字符串存储, 过滤值须同为字符串;
     *  NIN 语义对缺失 doc_id 的点不影响(缺失字段不匹配 → mustNot 通过 → 保留)。 */
    private List<Document> semanticHits(VectorStore store, String query, int topK, Set<Long> disabled) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query).topK(topK).similarityThreshold(0.0);
            if (!disabled.isEmpty()) {
                // 注意: 必须以 List<Object> 类型命中 nin(String, List<Object>) 重载——
                // List<String> 会因泛型不兼容选中 nin(String, Object...) 变长参数重载,
                // 整个 List 被当成单个值, 生成 NIN [["7"]] 这种失效过滤(单测已钉住)
                List<Object> ids = disabled.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.toList());
                builder.filterExpression(new FilterExpressionBuilder()
                        .nin("doc_id", ids)
                        .build());
            }
            return store.similaritySearch(builder.build());
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
