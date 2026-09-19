package com.ai.rag.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两路召回的合并与 RRF（倒数排名融合）。
 *
 * <p>RRF 只用名次不用原始分数，因此天然消解"语义相似度与 BM25 分数量纲不可比"的问题；
 * 常数 60 取业界默认值，写在 {@link #RRF_K} 供调参时定位。
 */
final class RrfFuser {

    /** RRF 平滑常数：越大越弱化头部名次的优势 */
    static final int RRF_K = 60;

    private RrfFuser() {
    }

    /**
     * 按分块去重合并两路命中，并返回 RRF 降序候选。
     *
     * <p>去重键为 {@code doc_id:chunk_index}（见 {@link DocumentMeta#chunkKey}）；
     * 同一分块两路都命中时保留语义路的文档原文，并把关键词路名次并进去重后的候选。
     *
     * @param semantic 语义路命中（已按相似度降序）
     * @param keyword  关键词路命中（已按 BM25 降序）
     * @return RRF 降序的候选列表（此时尚未计算融合分数）
     */
    static List<RetrievalCandidate> fuse(List<Document> semantic, List<Document> keyword) {
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        for (int i = 0; i < semantic.size(); i++) {
            Document d = semantic.get(i);
            merged.put(DocumentMeta.chunkKey(d),
                    new RetrievalCandidate(d, DocumentMeta.similarity(d), null, i + 1, null));
        }
        for (int i = 0; i < keyword.size(); i++) {
            Document d = keyword.get(i);
            String key = DocumentMeta.chunkKey(d);
            Double kw = DocumentMeta.keywordScore(d);
            int rank = i + 1;
            RetrievalCandidate existing = merged.get(key);
            merged.put(key, existing == null
                    ? new RetrievalCandidate(d, null, kw, null, rank)
                    : existing.withKeyword(kw, rank));
        }
        if (merged.isEmpty()) {
            return List.of();
        }

        Map<String, Double> rrf = new HashMap<>();
        merged.forEach((key, c) -> rrf.put(key, rrfScore(c)));
        List<RetrievalCandidate> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparingDouble((RetrievalCandidate c) ->
                rrf.get(DocumentMeta.chunkKey(c.doc()))).reversed());
        return ordered;
    }

    /** 单路命中按名次的 RRF 贡献相加（缺席的那路贡献 0） */
    private static double rrfScore(RetrievalCandidate c) {
        double score = 0;
        if (c.semRank() != null) {
            score += 1.0 / (RRF_K + c.semRank());
        }
        if (c.kwRank() != null) {
            score += 1.0 / (RRF_K + c.kwRank());
        }
        return score;
    }
}
