package com.ai.rag;

/**
 * RAG 意图路由结果(词表预判, 仅作审计对照标签, 不参与任何链路决策)：
 * <ul>
 *   <li>{@link #KB} —— 词表预判"像知识库问题"(命中 internal-keywords);</li>
 *   <li>{@link #GENERAL} —— 词表预判"像通用问题"; KB 与 GENERAL 都照常检索,</li>
 *   <li>{@link #TOOL} —— 词表预判"像工具问题"(命中 tool-keywords)。【已被替代】
 *       该预判曾用于短路跳过知识库检索, 已废除——按实体名词匹配会误判
 *       ("订单的报销制度是什么"), 严格模式下把知识库能答的问题错拒。</li>
 * </ul>
 * 作答依据一律由检索事实算出的 {@link ChatOutcome} 决定, 预判值仅供
 * "词表预判 vs 实际出口"的偏差审计。
 */
public enum RagMode {

    /** 需要检索知识库 */
    KB,

    /** 工具类问题, 答案在业务库, 跳过检索交给模型调工具 */
    TOOL,

    /** 无需检索，直接作答 */
    GENERAL
}
