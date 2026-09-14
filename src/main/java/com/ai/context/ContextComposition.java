package com.ai.context;

/**
 * 一次上下文装配的 Token 组成快照(可观测: 各段占用与截断/改写标记)。
 *
 * @param systemTokens    system 提示词(不含 RAG 检索内容)Token
 * @param historyTokens   历史段(摘要 + 最近消息)Token
 * @param summaryTokens   其中摘要段 Token
 * @param ragTokens       RAG 检索上下文 Token
 * @param userTokens      当前用户输入 Token
 * @param totalTokens     合计 Token
 * @param modelMaxTokens  模型上下文窗口上限
 * @param historyMessages 注入的历史消息条数(不含摘要)
 * @param truncated       是否发生窗口/预算截断
 * @param rewritten       当前问题是否经过多轮改写
 */
public record ContextComposition(int systemTokens, int historyTokens, int summaryTokens,
                                 int ragTokens, int userTokens, int totalTokens,
                                 int modelMaxTokens, int historyMessages,
                                 boolean truncated, boolean rewritten) {
}
