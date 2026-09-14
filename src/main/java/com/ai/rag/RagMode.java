package com.ai.rag;

/**
 * RAG 意图路由结果(对话前判定)：
 * <ul>
 *   <li>{@link #KB} —— 问题涉及企业知识/内部业务，需要执行检索(语义 + 关键词多路召回);</li>
 *   <li>{@link #GENERAL} —— 通用常识/闲聊类问题，跳过检索、以自由问答回答。</li>
 * </ul>
 */
public enum RagMode {

    /** 需要检索知识库 */
    KB,

    /** 无需检索，直接作答 */
    GENERAL
}
