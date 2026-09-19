package com.ai.rag.service;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code score} 模式（默认）：语义相似度与 BM25 归一化分数加权融合，纯计算零额外调用。
 *
 * <p>只有一路命中时该路分数即融合分（不做归一化，避免把单路结果压进 0.6/0.4 区间失真）。
 */
@Component
class ScoreFusionReranker implements RerankStrategy {

    /** 语义相似度权重（另一路取 {@code 1 - } 该值） */
    private static final double SEMANTIC_WEIGHT = 0.6;
    private static final double KEYWORD_WEIGHT = 1 - SEMANTIC_WEIGHT;

    @Override
    public String mode() {
        return "score";
    }

    /**
     * 按融合分数降序重排。
     *
     * @param query      检索问题（本策略不使用）
     * @param candidates RRF 顺序候选
     * @return 带 {@code fusedScore} 且降序的候选
     */
    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        double maxKw = candidates.stream()
                .map(RetrievalCandidate::kw)
                .filter(Objects::nonNull)
                .mapToDouble(v -> v).max().orElse(1.0);
        double kwDenominator = Math.max(maxKw, 1e-9);
        return candidates.stream()
                .map(c -> c.withFused(fused(c, kwDenominator)))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::fusedScore).reversed())
                .toList();
    }

    /** 两路都有→加权；只有语义→取语义分；只有关键词→取归一化 BM25 */
    private double fused(RetrievalCandidate c, double kwDenominator) {
        if (c.sem() != null && c.kw() != null) {
            return SEMANTIC_WEIGHT * Math.max(0, Math.min(1, c.sem()))
                    + KEYWORD_WEIGHT * (c.kw() / kwDenominator);
        }
        if (c.sem() != null) {
            return c.sem();
        }
        return c.kw() == null ? 0.0 : c.kw() / kwDenominator;
    }
}
