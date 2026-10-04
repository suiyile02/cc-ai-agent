package com.ai.memory;

import com.ai.memory.entity.ChatMemoryRaw;
import com.ai.memory.mapper.ChatMemoryRawMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ChatMemoryArchive} 的数据库实现(chat_memory_raw 表)。
 *
 * <p>调用方(ConversationMemoryService)以会话锁串行化写入, seq 取当前最大值+1 即保序;
 * 双写与工作表同事务, 追加失败随调用方回滚——留档缺失比对话失败更难补偿, 不做本地降级。
 */
@Component
@RequiredArgsConstructor
public class DbChatMemoryArchive implements ChatMemoryArchive {

    private final ChatMemoryRawMapper mapper;

    @Override
    public void append(String conversationId, List<Message> added) {
        long seq = nextSeq(conversationId);
        for (Message m : added) {
            ChatMemoryRaw row = new ChatMemoryRaw();
            row.setConversationId(conversationId);
            row.setSeq(++seq);
            row.setRole(m.getMessageType().name());
            row.setContent(m.getText() == null ? "" : m.getText());
            mapper.insert(row);
        }
    }

    @Override
    public void appendSummary(String conversationId, String summary) {
        ChatMemoryRaw row = new ChatMemoryRaw();
        row.setConversationId(conversationId);
        // +1: nextSeq 返回的是当前最大值, 直接用作新行 seq 会与最后一行消息撞 uk_conv_seq
        row.setSeq(nextSeq(conversationId) + 1);
        row.setRole("SUMMARY");
        row.setContent(summary);
        row.setBatch(nextBatch(conversationId));
        mapper.insert(row);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        mapper.delete(new QueryWrapper<ChatMemoryRaw>().eq("conversation_id", conversationId));
    }

    @Override
    public List<ArchivedEntry> findByConversationId(String conversationId, int limit) {
        return mapper.selectList(new QueryWrapper<ChatMemoryRaw>()
                        .eq("conversation_id", conversationId)
                        .orderByAsc("seq")
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(r -> new ArchivedEntry(r.getSeq(), r.getRole(), r.getContent(),
                        r.getBatch(), r.getCreatedAt()))
                .toList();
    }

    /** 当前最大序号(调用方持会话锁, 无并发竞争) */
    private long nextSeq(String conversationId) {
        List<Object> objs = mapper.selectObjs(new QueryWrapper<ChatMemoryRaw>()
                .select("COALESCE(MAX(seq),0)").eq("conversation_id", conversationId));
        return objs.isEmpty() || objs.get(0) == null ? 0L : ((Number) objs.get(0)).longValue();
    }

    /** 下一个压缩批次号(仅统计 SUMMARY 行) */
    private int nextBatch(String conversationId) {
        List<Object> objs = mapper.selectObjs(new QueryWrapper<ChatMemoryRaw>()
                .select("COALESCE(MAX(batch),0)").eq("conversation_id", conversationId)
                .eq("role", "SUMMARY"));
        return objs.isEmpty() || objs.get(0) == null ? 1 : ((Number) objs.get(0)).intValue() + 1;
    }
}
