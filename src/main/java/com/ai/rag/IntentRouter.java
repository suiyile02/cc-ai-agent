package com.ai.rag;

/**
 * 意图路由契约：按消息内容预判提问类别(KB/TOOL/GENERAL)。
 *
 * <p><b>纯审计标签</b>：P3-7 起它不再决定"这个问题要不要查知识库"(一律检索, 出口由检索事实算出)；
 * 曾保留的"TOOL 短路跳过检索"也已废除——词表按实体名词匹配会误判("订单的报销制度是什么")，
 * 在严格模式下把知识库能答的问题错拒。预判值仅随 {@code rag_decision_log.rag_mode} 留痕，
 * 供对照"词表预判 vs 实际出口"的偏差。当前实现为 {@link KeywordIntentRouter}(关键词匹配)。
 */
public interface IntentRouter {

    /**
     * 按消息内容路由意图。
     *
     * @param message 用户消息
     * @return 预判类别, 仅作审计留痕, 不影响检索与作答链路
     */
    RagMode route(String message);
}
