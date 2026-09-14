package com.ai.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次装配所需的历史上下文(由 {@link ConversationMemory#loadHistory} 产出)。
 *
 * @param summary       滚动摘要文本(可为 null/空)
 * @param messages      按时间升序、已受窗口与 Token 预算约束的最近消息
 * @param summaryTokens 摘要段 Token 估算(含 SYSTEM 开销; 无摘要为 0)
 * @param historyTokens 历史段总 Token 估算(摘要 + 消息)
 * @param truncated     是否发生了窗口/预算截断
 */
public record HistoryContext(String summary, List<Message> messages,
                             int summaryTokens, int historyTokens, boolean truncated) {

    /**
     * 空历史(无摘要、无消息)。
     *
     * <p>【未被引用】全项目无调用方(装配层始终走 loadHistory 返回真实历史)。
     * 保留作为空值工厂, 如确认无用可直接删除。
     */
    public static HistoryContext empty() {
        return new HistoryContext(null, List.of(), 0, 0, false);
    }
}
