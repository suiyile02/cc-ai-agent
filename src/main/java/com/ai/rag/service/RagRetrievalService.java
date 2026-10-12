package com.ai.rag.service;

import com.ai.common.Timeouts;
import com.ai.common.TokenCounter;
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
 * 无超时时端点挂起会把对话整体拖住。
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
    private final TokenCounter tokenCounter;

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
     * @param query       检索问题
     * @param topK        最终注入 Top-K
     * @param threshold   语义路相似度阈值
     * @param tokenBudget RAG 段 Token 预算(≤0 不限; 展开层据此对父块分级降级)
     * @return 检索结果（命中 + 各路过计数 + 是否执行）
     */
    @Override
    public RetrievalOutcome retrieveOutcome(String query, int topK, double threshold, int tokenBudget) {
        long timeoutMs = appProperties.getRag().getRetrieveTimeoutMs();
        long start = System.currentTimeMillis();
        try {
            RetrievalOutcome outcome = timeoutMs > 0
                    ? Timeouts.call(() -> doRetrieve(query, topK, threshold, tokenBudget), timeoutMs)
                    : doRetrieve(query, topK, threshold, tokenBudget);
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
     * <p>顺序是 <b>召回 → 融合 → 重排 → 阈值过滤 → 父块展开</b>：阈值刻意排在重排之后。
     * 判"有没有依据"用的是语义路最大分({@code semanticMaxScore}, 见
     * {@link com.ai.rag.OutcomeResolver}), 它与过滤时机无关, 所以出口结论不变；变的是注入内容——
     * 重排得以看到全部候选，第 6 名那种"余弦分低但确实答得上"的段落才有机会被捞进上下文。
     * 父块展开在阈值过滤后按剩余预算分级(见 {@link #expandToParents})。
     *
     * @param query       检索问题
     * @param topK        最终注入 Top-K
     * @param threshold   语义路相似度阈值
     * @param tokenBudget RAG 段 Token 预算(≤0 不限)
     * @return 检索结果
     */
    private RetrievalOutcome doRetrieve(String query, int topK, double threshold, int tokenBudget) {
        // 两路召回
        HybridRecaller.Recall recall = recaller.recall(query, topK);
        if (recall.nothingUsable()) {
            log.warn("向量库与关键词索引均不可用(未配置 Embedding 或未入库)，RAG 降级为空上下文");
            return RetrievalOutcome.none();
        }
        log.info("RAG 检索关键字: [{}]", query);
        // ★ BM25 明细刻意在融合前打: RrfFuser 两路同块时保留语义路 Document, 合并后关键词路的
        //   索引主键(id "kw-{docId}:{chunkIndex}")即丢失——重启重建后键位与 payload chunk_index
        //   可能不一致(注册序 vs 原始序), 只有召回现场能暴露。
        log.info("语义检索命中 {} 段(阈值过滤前): {}", recall.semantic().size(), describe(recall.semantic()));
        log.info("BM25 检索命中 {} 段: {}", recall.keyword().size(), describeKeyword(recall.keyword()));
        // 关键词路只在 hybrid 开启且索引非空时才有内容, 故"有关键词命中"等价于历史的两路合并条件
        List<Document> hits = recall.keyword().isEmpty()
                ? recall.semantic()
                : mergeAndRerank(query, recall, topK);
        log.info("重排后 Top-{}: {}", Math.min(topK, hits.size()), describe(hits));
        List<Document> finalHits = expandToParents(filterByThreshold(hits, threshold), tokenBudget);
        return new RetrievalOutcome(finalHits, true,
                countPassed(recall.semantic(), threshold), recall.keyword().size(),
                false, recall.semanticMaxScore());
    }

    /**
     * 把候选渲染成单行诊断串: {@code doc_id:chunk_index(cos=余弦分, kw=BM25分) "≤80字片段"}。
     * 纯 BM25 命中无余弦分、语义点无 BM25 分时对应项显示 {@code -}。仅用于日志输出。
     */
    private static String describe(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (Document d : docs) {
            Double cos = DocumentMeta.semanticScore(d);
            Double kw = DocumentMeta.keywordScore(d);
            String text = d.getText() == null ? "" : d.getText().replace('\n', ' ');
            if (text.length() > 80) {
                text = text.substring(0, 80) + "…";
            }
            // 父块展开后 text 已换成整节, chunkIndex 仍指命中小块——同时打印 parent_ref 便于键位核对
            Object parentRef = d.getMetadata() == null ? null : d.getMetadata().get("parent_ref");
            sb.append(String.format("%n  [%s:%d%s cos=%s kw=%s] %s",
                    DocumentMeta.docId(d), DocumentMeta.chunkIndex(d),
                    parentRef == null ? "" : "/" + parentRef,
                    cos == null ? "-" : String.format("%.4f", cos),
                    kw == null ? "-" : String.format("%.4f", kw),
                    text));
        }
        return sb.toString();
    }

    /**
     * BM25 召回专用渲染: 显示索引内部主键 {@code docId:chunkIndex}(合成 id 的 {@code kw-} 后缀,
     * 键位漂移的现场证据)与块首文本。{@link #describe} 用于语义/重排候选(那里的 chunk_index 是向量点元数据)。
     */
    private static String describeKeyword(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (Document d : docs) {
            String id = d.getId() == null ? "" : d.getId();
            String key = id.startsWith(DocumentMeta.KEYWORD_ID_PREFIX)
                    ? id.substring(DocumentMeta.KEYWORD_ID_PREFIX.length()) : "-";
            String text = d.getText() == null ? "" : d.getText().replace('\n', ' ');
            if (text.length() > 80) {
                text = text.substring(0, 80) + "…";
            }
            Double kw = DocumentMeta.keywordScore(d);
            sb.append(String.format("%n  [key=%s kw=%s] %s",
                    key,
                    kw == null ? "-" : String.format("%.4f", kw),
                    text));
        }
        return sb.toString();
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
     * <p><b>预算感知分级展开(2026-10-09)</b>: 按剩余 Token 预算对每个命中选择注入粒度——
     * 预算够装整父块 → 整父块; 不够但够装"命中小块附近窗口" → 窗口; 再不够 → 回退小块原文。
     * 避免"一个超大父块吃光全部预算、后面父块被渲染层硬切"的旧问题(预算只在渲染层截断,
     * 会把父块拦腰砍断且互相挤占)。预算耗尽后的命中仍保留(不扣预算, 交给渲染层最终把关),
     * 保证来源/计数/出口判定不受影响。
     *
     * <p>降级: 父块读取失败/父块缺失时保留小块原文(不因父块查询失败而降级为无资料)。
     * 父块展开后 text 变化, 但 source 展示仍指向命中小块(来源正确)。
     *
     * @param hits        阈值过滤后的命中小块(按重排分数降序)
     * @param tokenBudget RAG 段 Token 预算(≤0 表示不限, 退化为全量展开的旧行为)
     * @return 展开/降级后的命中文档(数量与顺序不变)
     */
    private List<Document> expandToParents(List<Document> hits, int tokenBudget) {
        if (hits == null || hits.isEmpty()) {
            return hits;
        }
        // 收集需要展开的 (docId, parentRef) 去重; Number/数字串都认(同 DocumentMeta.docId 口径)
        Map<Long, Set<Integer>> need = new java.util.LinkedHashMap<>();
        for (Document hit : hits) {
            Long docId = DocumentMeta.docId(hit);
            Integer parentRef = metaInt(hit.getMetadata().get("parent_ref"));
            if (docId != null && parentRef != null) {
                need.computeIfAbsent(docId, k -> new java.util.HashSet<>()).add(parentRef);
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
        // 按剩余预算对每个命中分级展开
        int windowChars = Math.max(0, appProperties.getRag().getParentWindowChars());
        int remaining = tokenBudget > 0 ? tokenBudget : Integer.MAX_VALUE;
        List<Document> expanded = new java.util.ArrayList<>(hits.size());
        for (Document hit : hits) {
            Long docId = DocumentMeta.docId(hit);
            Integer parentRef = metaInt(hit.getMetadata().get("parent_ref"));
            String parentText = (docId == null || parentRef == null)
                    ? null : parentTexts.get(docId + ":" + parentRef);
            if (parentText == null || parentText.isBlank()) {
                expanded.add(hit); // 无父块: 保留小块原文
                continue;
            }
            int parentTokens = tokenCounter.count(parentText);
            if (parentTokens <= remaining) {
                remaining -= parentTokens;
                expanded.add(withText(hit, parentText));
                continue;
            }
            // 预算不够装整父块: 尝试命中小块附近窗口(父块内以小块原文为锚点)
            String smallText = hit.getText();
            String window = windowAround(parentText, smallText, windowChars);
            if (window != null) {
                int windowTokens = tokenCounter.count(window);
                if (windowTokens <= remaining) {
                    remaining -= windowTokens;
                    expanded.add(withText(hit, window));
                    continue;
                }
            }
            // 窗口也放不下: 回退小块原文(不扣预算, 渲染层最终把关)
            expanded.add(hit);
        }
        return expanded;
    }

    /** 以小块原文在父块内的位置为中心, 取两侧各 {@code windowChars} 字符(对齐到行边界)的窗口。 */
    private String windowAround(String parentText, String smallText, int windowChars) {
        if (smallText == null || smallText.isBlank() || windowChars <= 0) {
            return null;
        }
        int idx = parentText.indexOf(smallText);
        if (idx < 0) {
            return null;
        }
        int start = Math.max(0, idx - windowChars);
        int end = Math.min(parentText.length(), idx + smallText.length() + windowChars);
        // 对齐到最近的行边界, 避免窗口从句子中间切开
        int lineStart = parentText.lastIndexOf('\n', start);
        start = lineStart < 0 ? 0 : lineStart + 1;
        int lineEnd = parentText.indexOf('\n', end);
        end = lineEnd < 0 ? parentText.length() : lineEnd;
        return start < end ? parentText.substring(start, end) : null;
    }

    /** 用新文本重建 Document(id/score/metadata 原样, 仅替换 text)。 */
    private Document withText(Document hit, String text) {
        return Document.builder()
                .id(hit.getId())
                .text(text)
                .score(hit.getScore())
                .metadata(hit.getMetadata())
                .build();
    }

    /** metadata 数值读取: Number 与数字串都认(向量库 payload 可能以字符串存储)。 */
    private static Integer metaInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
