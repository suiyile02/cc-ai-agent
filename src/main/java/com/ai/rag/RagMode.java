package com.ai.rag;

/**
 * RAG 意图路由结果(对话前判定)：
 * <ul>
 *   <li>{@link #KB} —— 问题涉及企业知识/内部业务，需要执行检索(语义 + 关键词多路召回);</li>
 *   <li>{@link #TOOL} —— 工具类问题(查订单/查物流等)，答案在业务库、需调用 BusinessTools，
 *       知识库检索查不到，跳过检索直接交给模型自主调工具;</li>
 *   <li>{@link #GENERAL} —— 通用常识/闲聊类问题，跳过检索、以自由问答回答。</li>
 * </ul>
 */
public enum RagMode {

    /** 需要检索知识库 */
    KB,

    /** 工具类问题, 答案在业务库, 跳过检索交给模型调工具 */
    TOOL,

    /** 无需检索，直接作答 */
    GENERAL
}
