package com.ai.rag.service;

import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.SourceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索编排：多路召回 → RRF 融合 → 重排 → Top-K（需求 3.3 + 混合检索增强）。
 *
 * <p>本类只做编排与降级，各环节各有归属：{@link HybridRecaller}（召回）、{@link RrfFuser}（去重融合）、
 * {@link RerankStrategy}（重排，模式由 {@code app.rag.rerank-mode} 选择）、{@link RagContextRenderer}（注入文本渲染）。
 * 对外以 {@link RagRetriever} 契约暴露，供 chat/context 模块依赖接口调用。
 *
 * <p>整体受 {@code app.rag.retrieve-timeout-ms} 限时：嵌入与向量库是外部网络调用，
 * 无超时时端点挂起会把对话整体拖住（实测出现过 100s）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService implements RagRetriever {

    private final AppProperties appProperties;
    private final HybridRecaller recaller;
    private final RerankStrategyFactory rerankers;
    private final RagContextRenderer renderer;

    /**
     * 按指定 Top-K / 阈值检索（检索调试接口使用）。
     *
     * @param query     检索问题
     * @param topK      召回数量上限
     * @param threshold 语义相似度阈值（低于该值不注入）
     * @return 重排后的命中文档（可能为空）
     */
    @Override
    public List<Document> retrieve(String query, int topK, double threshold) {
        return retrieveOutcome(query, topK, threshold).hits();
    }

    /**
     * 混合检索主入口，整段受限时保护。
     *
     * <p>超时/异常降级为 {@link RetrievalOutcome#executedEmpty()}（已执行、零命中），
     * 与"未执行检索"区分开——后者是语义缓存命中的专属标记（审计不变式）。
     *
     * @param query     检索问题
     * @param topK      最终注入 Top-K
     * @param threshold 语义路相似度阈值
     * @return 检索结果（命中 + 各路过计数 + 是否执行）
     */
    @Override
    public RetrievalOutcome retrieveOutcome(String query, int topK, double threshold) {
        long timeoutMs = appProperties.getRag().getRetrieveTimeoutMs();
        long start = System.currentTimeMillis();
        try {
            RetrievalOutcome outcome = timeoutMs > 0
                    ? Timeouts.call(() -> doRetrieve(query, topK, threshold), timeoutMs)
                    : doRetrieve(query, topK, threshold);
            log.info("RAG 检索完成: {}ms, 语义 {} + 关键词 {} -> 最终 {} 段",
                    System.currentTimeMillis() - start, outcome.semanticCount(),
                    outcome.keywordCount(), outcome.hits().size());
            return outcome;
        } catch (Exception e) {
            log.warn("RAG 检索超时/失败(预算 {}ms, 实际 {}ms), 本轮降级为空上下文: {}",
                    timeoutMs, System.currentTimeMillis() - start, e.getMessage());
            return RetrievalOutcome.executedEmpty();
        }
    }

    /**
     * 检索执行体（被 {@link #retrieveOutcome} 包在限时调用内）。
     *
     * @param query     检索问题
     * @param topK      最终注入 Top-K
     * @param threshold 语义路相似度阈值
     * @return 检索结果
     */
    private RetrievalOutcome doRetrieve(String query, int topK, double threshold) {
        HybridRecaller.Recall recall = recaller.recall(query, topK, threshold);
        if (recall.nothingUsable()) {
            log.warn("向量库与关键词索引均不可用(未配置 Embedding 或未入库)，RAG 降级为空上下文");
            return RetrievalOutcome.none();
        }
        // 关键词路只在 hybrid 开启且索引非空时才有内容, 故"有关键词命中"等价于历史的两路合并条件
        List<Document> hits = recall.keyword().isEmpty()
                ? recall.semantic()
                : mergeAndRerank(query, recall, topK);
        return new RetrievalOutcome(hits, true, recall.semantic().size(), recall.keyword().size(),
                false, recall.semanticMaxScore());
    }

    /**
     * 两路合并重排并收敛到 Top-K。
     *
     * @param query  检索问题（LLM 重排需要）
     * @param recall 两路召回结果
     * @param topK   最终条数
     * @return 重排后的命中文档（携带 {@code rerank_score}）
     */
    private List<Document> mergeAndRerank(String query, HybridRecaller.Recall recall, int topK) {
        List<RetrievalCandidate> fused = RrfFuser.fuse(recall.semantic(), recall.keyword());
        return rerankers.current().rerank(query, fused).stream()
                .limit(topK)
                .map(RetrievalCandidate::toDocument)
                .toList();
    }

    /**
     * 组装注入 LLM 的上下文文本（受 Token 预算与字符上限双重约束）。
     *
     * @param hits        最终命中文档（已按相关度排序）
     * @param tokenBudget RAG 段 Token 预算（≤0 表示不限 Token）
     * @return 上下文串；无命中返回空串
     */
    @Override
    public String buildContext(List<Document> hits, int tokenBudget) {
        return renderer.render(hits, tokenBudget);
    }

    /**
     * 命中 → 来源列表（落 {@code chat_log.sources}，不返回前端）。
     *
     * @param hits 最终命中文档
     * @return 来源列表（文件/分块/片段/分数）
     */
    @Override
    public List<SourceVO> toSources(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .map(doc -> new SourceVO(DocumentMeta.fileName(doc), DocumentMeta.docId(doc),
                        DocumentMeta.chunkIndex(doc), DocumentMeta.snippet(doc),
                        DocumentMeta.similarity(doc), DocumentMeta.semanticScore(doc),
                        DocumentMeta.keywordScore(doc)))
                .toList();
    }
}
