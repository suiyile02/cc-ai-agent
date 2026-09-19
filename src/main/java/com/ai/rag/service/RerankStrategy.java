package com.ai.rag.service;

import java.util.List;

/**
 * 重排策略：在 RRF 顺序之上决定最终 Top-K 的次序。
 *
 * <p>由 {@code app.rag.rerank-mode} 选择实现；接入真实重排模型（如 gte-rerank）时
 * 只需新增一个实现并由 {@link RerankStrategyFactory} 按 mode 取用，不改编排层。
 */
interface RerankStrategy {

    /**
     * 本策略对应的配置值（与 {@code rerank-mode} 取值一致）。
     *
     * @return mode 字符串，如 {@code score}
     */
    String mode();

    /**
     * 对 RRF 降序的候选重排。
     *
     * @param query      检索问题（需要理解语义的策略使用，其余可忽略）
     * @param candidates RRF 顺序的候选（非空）
     * @return 重排后的候选（同长度，顺序可能改变）
     */
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);
}
