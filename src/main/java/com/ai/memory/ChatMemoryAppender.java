package com.ai.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 会话记忆追加契约：以 append-only 方式写入新消息(不重写既有历史)。
 *
 * <p>替代原 saveAll 的"全删全插"写放大(每轮 O(n) 次 SQL 变为 O(新增) 次),
 * 且保留消息真实时间戳。由 {@link DbChatMemoryRepository} 实现,
 * 供上下文管线(ConversationMemoryService)在每轮对话写回时调用。
 */
public interface ChatMemoryAppender {

    /**
     * 追加新消息到会话历史(只插入新增行, 不触碰既有记录)。
     *
     * @param conversationId 会话 ID
     * @param newMessages    新增消息(通常为本轮用户提问 + 助手回答)
     */
    void append(String conversationId, List<Message> newMessages);
}
