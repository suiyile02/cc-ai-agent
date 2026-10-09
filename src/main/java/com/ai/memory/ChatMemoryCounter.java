package com.ai.memory;

/**
 * 会话记忆统计契约：只计数, 不加载消息内容。
 *
 * <p>供查询改写判断"历史是否够一轮"({@code min-history-turns}): 用数据库 COUNT(*) 只计数不加载内容,
 * 单轮开销与历史长度无关(避免取出全部消息逐条反序列化只为数个数)。
 *
 * <p>与 {@link ChatMemoryAppender}(写入契约)成对, 由 {@code DbChatMemoryRepository} 一并实现。
 */
public interface ChatMemoryCounter {

    /**
     * 统计会话历史消息条数(不加载内容)。
     *
     * @param conversationId 会话 ID
     * @return 消息条数; 无历史返回 0
     */
    long countByConversationId(String conversationId);
}
