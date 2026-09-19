package com.ai.rag.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * {@code none} 模式：不重排，直接沿用 RRF 顺序。
 *
 * <p>用于对照实验或确定"融合排名已够用"的场景；此时 Top-K 完全由两路名次决定。
 */
@Component
class RrfOrderReranker implements RerankStrategy {

    /**
     * 原序返回，并补齐融合分数（取 RRF 顺位递减的相对分，仅用于日志与来源展示）。
     *
     * @param query      检索问题（不使用）
     * @param candidates RRF 顺序候选
     * @return 同序候选
     */
    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        int size = candidates.size();
        return IntStream.range(0, size)
                .mapToObj(i -> candidates.get(i).withFused((double) (size - i) / size))
                .toList();
    }

    @Override
    public String mode() {
        return "none";
    }
}
