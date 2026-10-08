package com.ai.rag.service;

import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.observability.ChatMetrics;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.SourceVO;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private final com.ai.knowledge.ParentBlockStore parentBlockStore;

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
            long cost = System.currentTimeMillis() - start;
            Metrics
                    .timer(ChatMetrics.RETRIEVAL)
                    .record(cost, TimeUnit.MILLISECONDS);
            log.info("RAG 检索完成: {}ms, 语义 {} + 关键词 {} -> 最终 {} 段",
                    cost, outcome.semanticCount(),
                    outcome.keywordCount(), outcome.hits().size());
            return outcome;
        } catch (Exception e) {
            Metrics
                    .counter(ChatMetrics.RETRIEVAL_DEGRADED).increment();
            log.warn("RAG 检索超时/失败(预算 {}ms, 实际 {}ms), 本轮降级为空上下文: {}",
                    timeoutMs, System.currentTimeMillis() - start, e.getMessage());
            return RetrievalOutcome.executedEmpty();
        }
    }

    /**
     * 检索执行体（被 {@link #retrieveOutcome} 包在限时调用内）。
     *
     * <p>顺序是 <b>召回 → 融合 → 重排 → 阈值过滤 → Top-K</b>：阈值刻意排在重排之后。
     * 判"有没有依据"用的是语义路最大分({@code semanticMaxScore}, 见
     * {@link com.ai.rag.OutcomeResolver}), 它与过滤时机无关, 所以出口结论不变；变的是注入内容——
     * 重排得以看到全部候选，第 6 名那种"余弦分低但确实答得上"的段落才有机会被捞进上下文。
     *
     * @param query     检索问题
     * @param topK      最终注入 Top-K
     * @param threshold 语义路相似度阈值
     * @return 检索结果
     */
    private RetrievalOutcome doRetrieve(String query, int topK, double threshold) {
        // 两路召回
        HybridRecaller.Recall recall = recaller.recall(query, topK);
        if (recall.nothingUsable()) {
            log.warn("向量库与关键词索引均不可用(未配置 Embedding 或未入库)，RAG 降级为空上下文");
            return RetrievalOutcome.none();
        }
        // 关键词路只在 hybrid 开启且索引非空时才有内容, 故"有关键词命中"等价于历史的两路合并条件
        List<Document> hits = recall.keyword().isEmpty()
                ? recall.semantic()
                : mergeAndRerank(query, recall, topK);
        List<Document> finalHits = expandToParents(filterByThreshold(hits, threshold));
        return new RetrievalOutcome(finalHits, true,
                countPassed(recall.semantic(), threshold), recall.keyword().size(),
                false, recall.semanticMaxScore());
    }

    /**
     * 阈值过滤: 只看语义路的余弦分, 没有该分的候选一律保留。
     *
     * <p><b>读 metadata 而非 {@code doc.getScore()}</b>: 重排之后 {@code score} 已被融合分/模型相关度分
     * 占用(不同策略不同量纲), 语义原始分只挂在 {@code semantic_score} 上——读错字段等于阈值失效。
     *
     * <p>"无余弦分"只有两种来源——纯 BM25 命中(词面命中不代表语义相关, 但它已由重排竞争过名次),
     * 或向量库实现没回填分数。后者若按 0 处理会把整路清零, 因此这里选择保留(与原实现同口径)。
     */
    static List<Document> filterByThreshold(List<Document> hits, double threshold) {
        return hits.stream().filter(d -> {
            Double semantic = DocumentMeta.semanticScore(d);
            return semantic == null || semantic >= threshold;
        }).toList();
    }

    /** 语义路过阈条数(仅审计计数)。 */
    private static int countPassed(List<Document> semantic, double threshold) {
        return (int) semantic.stream()
                .filter(d -> {
                    Double s = DocumentMeta.similarity(d);
                    return s == null || s >= threshold;
                })
                .count();
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
     * 父子文档(小-大检索)展开: 命中小块若带 {@code doc_id + parent_ref}, 用其父块文本替换小块文本,
     * 使"检索粒度(小块)"与"上下文粒度(父块)"分离——召回仍按小块分数, 注入喂给模型的是完整父块。
     *
     * <p>降级: 父块读取失败/父块缺失时保留小块原文(不因父块查询失败而降级为无资料)。
     * 父块展开后 text 变化, 但 source 展示仍指向命中小块(来源正确)。
     */
    private List<Document> expandToParents(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return hits;
        }
        // 收集需要展开的 (docId, parentRef) 去重
        Map<Long, Set<Integer>> need = new java.util.LinkedHashMap<>();
        for (Document hit : hits) {
            Object docIdObj = hit.getMetadata().get("doc_id");
            Object parentRefObj = hit.getMetadata().get("parent_ref");
            if (docIdObj instanceof Number d && parentRefObj instanceof Number pr) {
                need.computeIfAbsent(d.longValue(), k -> new java.util.HashSet<>())
                        .add(pr.intValue());
            }
        }
        if (need.isEmpty()) {
            return hits;
        }
        // 批量取父块
        Map<String, String> parentTexts = new java.util.HashMap<>();
        need.forEach((docId, seqs) ->
                parentBlockStore.findParents(docId, new java.util.ArrayList<>(seqs))
                        .forEach((seq, text) -> parentTexts.put(docId + ":" + seq, text)));
        if (parentTexts.isEmpty()) {
            return hits;
        }
        // 替换命中小块文本为父块文本
        List<Document> expanded = new java.util.ArrayList<>(hits.size());
        for (Document hit : hits) {
            Object docIdObj = hit.getMetadata().get("doc_id");
            Object parentRefObj = hit.getMetadata().get("parent_ref");
            String parentText = null;
            if (docIdObj instanceof Number d && parentRefObj instanceof Number pr) {
                parentText = parentTexts.get(d.longValue() + ":" + pr.intValue());
            }
            if (parentText != null && !parentText.isBlank()) {
                expanded.add(Document.builder()
                        .id(hit.getId())
                        .text(parentText)
                        .score(hit.getScore())
                        .metadata(hit.getMetadata())
                        .build());
            } else {
                expanded.add(hit); // 无父块: 保留小块原文
            }
        }
        return expanded;
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
