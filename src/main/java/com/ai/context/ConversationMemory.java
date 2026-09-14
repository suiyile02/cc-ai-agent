package com.ai.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话记忆契约：历史读取(滚动摘要 + Token 受限窗口)/写回/清理，
 * 供对话(chat)、会话(session)等其它业务模块调用。
 *
 * <p>当前实现为 {@link ConversationMemoryService}(基于 SPRING_AI_CHAT_MEMORY
 * 与 conversation_summary 滚动摘要)；实现可替换, 调用方不感知。
 */
public interface ConversationMemory {

    /**
     * 读取会话历史：必要时触发滚动摘要压缩, 再按硬窗口与 Token 预算收敛。
     *
     * @param sessionId          会话 ID
     * @param historyTokenBudget 历史段可用 Token 预算(含摘要)
     * @return 历史上下文(摘要 + 最近消息 + Token 计量)
     */
    HistoryContext loadHistory(String sessionId, int historyTokenBudget);

    /**
     * 写回本轮对话(用户提问 + 助手回答)到会话记忆。
     *
     * @param sessionId     会话 ID
     * @param userText      用户消息(空则跳过)
     * @param assistantText 助手回答(空则跳过)
     */
    void append(String sessionId, String userText, String assistantText);

    /**
     * 统计最近历史轮数(用于查询改写的触发判定)。
     *
     * @param sessionId 会话 ID
     * @return 历史消息条数
     */
    int messageCount(String sessionId);

    /**
     * 返回最近的若干条历史消息(升序), 供查询改写参考。
     *
     * @param sessionId 会话 ID
     * @param limit     最多返回条数
     * @return 最近消息(升序)
     */
    List<Message> recentMessages(String sessionId, int limit);

    /**
     * 清理会话摘要(会话删除时调用)。
     *
     * @param sessionId 会话 ID
     */
    void clearSummary(String sessionId);

    /**
     * 读取会话滚动摘要文本(会话历史回显用)。
     *
     * @param sessionId 会话 ID
     * @return 摘要文本, 无摘要返回 null
     */
    String summaryOf(String sessionId);

    /**
     * 异步滚动摘要：写回记忆后调用, 满足触发条件(条数/Token 超阈值)时在后台
     * 合并既有摘要并生成新摘要, 随后裁剪原始历史; 不阻塞调用方, 失败保留现状待下轮再触发。
     *
     * @param sessionId 会话 ID
     */
    void summarizeIfNeededAsync(String sessionId);
}
