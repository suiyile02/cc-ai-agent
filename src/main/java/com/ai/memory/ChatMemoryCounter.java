package com.ai.memory;

/**
 * 会话记忆统计契约：只计数, 不加载消息内容。
 *
 * <p>背景: 查询改写每轮都要判断"历史是否够一轮"({@code min-history-turns}), 原先用
 * {@code findByConversationId(...).size()} 实现——把该会话全部消息取出并逐条 JSON 反序列化
 * 只为了数个数, 属每轮固定浪费; 改为数据库 COUNT(*) 后单轮开销与历史长度无关。
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
