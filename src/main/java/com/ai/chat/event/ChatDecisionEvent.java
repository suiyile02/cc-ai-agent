package com.ai.chat.event;

/**
 * 意图路由/检索决策快照(对话模块在调用模型前发布), 由 system 模块异步落 `rag_decision_log`。
 *
 * <p>刻意只带**值**而非实体：审计口径(如 retrievalExecuted 的算法)属对话模块知识,
 * 存储结构属 system, 事件是把两者解耦的边界。
 *
 * @param sessionId          会话 ID
 * @param userId             所属用户(授权过滤用)
 * @param userMessage        用户消息(已截断)
 * @param ragMode            意图判定 KB/TOOL/GENERAL
 * @param sessionType        会话类型 RAG/AGENT/HYBRID
 * @param retrievalExecuted  本轮是否真实执行了检索(KB 且未执行为缓存命中)
 * @param semanticHits       语义向量路命中数
 * @param keywordHits        关键词 BM25 路命中数
 * @param finalHits          最终注入上下文的命中数
 * @param topK               生效的 Top-K
 * @param similarityThreshold 生效的相似度阈值
 * @param semanticMaxScore    语义路**阈值过滤前**的最大相似度; **null = 本轮未执行检索**(被意图路由跳过),
 *                            0.0 = 执行了但该路无分数可得。两者必须可区分, 否则据此统计出的分数分布仍是错的。
 *                            阈值定标(P3-6)与相关性判据(P3-7 四出口)的唯一观察依据
 * @param rerankMode         生效的重排模式 score/llm/none
 * @param durationMs         路由与检索耗时
 */
public record ChatDecisionEvent(String sessionId, Long userId, String userMessage, String ragMode,
                                String sessionType, boolean retrievalExecuted,
                                int semanticHits, int keywordHits, int finalHits,
                                int topK, double similarityThreshold, Double semanticMaxScore,
                                String rerankMode, long durationMs) {
}
