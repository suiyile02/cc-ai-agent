package com.ai.memory;

import org.springframework.ai.chat.messages.Message;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话原始轨迹留档契约(append-only)：滚动摘要裁剪工作表的同时, 原始消息与压缩事件
 * 完整留存于本档案, 使压缩可逆、摘要策略可离线重算、"模型当时看到了什么"可回放。
 *
 * <p>写入点: ① 每轮写回时与工作表( SPRING_AI_CHAT_MEMORY)双写; ② 每次滚动摘要完成时
 * 追加一条 SUMMARY 行(自描述压缩事件, 携带递增批次号)。由 {@link DbChatMemoryArchive} 实现;
 * 行随会话删除清理, 无独立保留期。
 */
public interface ChatMemoryArchive {

    /** 归档条目(只读快照) */
    record ArchivedEntry(long seq, String role, String content, Integer batch, LocalDateTime createdAt) {
    }

    /**
     * 追加消息原文(与工作表写入同事务; 顺序由调用方的会话锁保证)。
     *
     * @param conversationId 会话 ID
     * @param added          新增消息(通常为本轮用户提问 + 助手回答)
     */
    void append(String conversationId, List<Message> added);

    /**
     * 追加一条滚动摘要压缩事件(批次号取该会话已有 SUMMARY 行最大值 +1)。
     *
     * @param conversationId 会话 ID
     * @param summary        本次生成的摘要全文
     */
    void appendSummary(String conversationId, String summary);

    /**
     * 清理某会话的全部留档(会话删除时调用)。
     *
     * @param conversationId 会话 ID
     */
    void deleteByConversationId(String conversationId);

    /**
     * 按序读取留档(升序)。
     *
     * @param conversationId 会话 ID
     * @param limit          最多返回条数
     * @return 留档条目(升序)
     */
    List<ArchivedEntry> findByConversationId(String conversationId, int limit);
}
