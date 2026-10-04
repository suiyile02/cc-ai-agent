package com.ai.memory;

import com.ai.memory.entity.ChatMemoryRecord;
import com.ai.memory.mapper.ChatMemoryRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DbChatMemoryRepository} 单元测试: content 列的 JSON 序列化/还原、
 * **旧格式兼容**(JSON 字符串节点)与坏数据跳过、append-only 语义(saveAll 才先删后插)、
 * TOOL/空文本过滤、按类型还原消息。
 */
class DbChatMemoryRepositoryTest {

    private final ChatMemoryRecordMapper mapper = mock(ChatMemoryRecordMapper.class);
    private final DbChatMemoryRepository repository =
            new DbChatMemoryRepository(mapper, new ObjectMapper());
    private static final String CID = "conv-1";

    private static ChatMemoryRecord record(String type, String content) {
        ChatMemoryRecord r = new ChatMemoryRecord();
        r.setConversationId(CID);
        r.setType(type);
        r.setContent(content);
        return r;
    }

    @Test
    void appendOnlyInsertsAndNeverDeletes() {
        repository.append(CID, List.of(new UserMessage("第一问"), new AssistantMessage("第一答")));

        // append-only 是硬约定: 追加路径绝不允许触发删除
        verify(mapper, never()).delete(any());
        ArgumentCaptor<ChatMemoryRecord> rows = ArgumentCaptor.forClass(ChatMemoryRecord.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(rows.capture());
        assertEquals("{\"text\":\"第一问\"}", rows.getAllValues().get(0).getContent());
        assertEquals("USER", rows.getAllValues().get(0).getType());
        assertEquals("ASSISTANT", rows.getAllValues().get(1).getType());
    }

    @Test
    void appendFiltersToolMessagesAndBlankText() {
        // TOOL 类型消息不入记忆; 空白文本不入记忆(Spring AI 的 Message 接口匿名实现构造)
        Message toolMessage = new Message() {
            @Override public MessageType getMessageType() { return MessageType.TOOL; }
            @Override public String getText() { return "{\"tool\":1}"; }
            @Override public java.util.Map<String, Object> getMetadata() { return java.util.Map.of(); }
        };
        Message blank = new Message() {
            @Override public MessageType getMessageType() { return MessageType.USER; }
            @Override public String getText() { return "   "; }
            @Override public java.util.Map<String, Object> getMetadata() { return java.util.Map.of(); }
        };

        repository.append(CID, List.of(blank, toolMessage, new UserMessage("有效提问")));

        ArgumentCaptor<ChatMemoryRecord> rows = ArgumentCaptor.forClass(ChatMemoryRecord.class);
        verify(mapper, org.mockito.Mockito.times(1)).insert(rows.capture());
        assertEquals("{\"text\":\"有效提问\"}", rows.getValue().getContent());
        verify(mapper, never()).delete(any());
    }

    @Test
    void saveAllDeletesFirstThenRewritesWindow() {
        InOrder inOrder = inOrder(mapper);
        List<Message> window = List.of(
                new SystemMessage("摘要块"),
                new UserMessage("问"),
                new AssistantMessage("答"));

        repository.saveAll(CID, window);

        inOrder.verify(mapper).delete(any());
        ArgumentCaptor<ChatMemoryRecord> rows = ArgumentCaptor.forClass(ChatMemoryRecord.class);
        verify(mapper, org.mockito.Mockito.times(3)).insert(rows.capture());
        // 时间戳按序递增(now+seq), 保证回放顺序稳定
        assertTrue(rows.getAllValues().get(0).getTimestamp()
                .isBefore(rows.getAllValues().get(2).getTimestamp()));
    }

    @Test
    void findByConversationIdRestoresMessagesByType() {
        when(mapper.selectList(any())).thenReturn(List.of(
                record("USER", "{\"text\":\"第一问\"}"),
                record("ASSISTANT", "{\"text\":\"第一答\"}"),
                record("SYSTEM", "{\"text\":\"【早前对话摘要】…\"}")));

        List<Message> messages = repository.findByConversationId(CID);

        assertEquals(3, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertInstanceOf(SystemMessage.class, messages.get(2));
        assertEquals("第一问", messages.get(0).getText());
    }

    @Test
    void legacyAndBrokenContentRowsAreHandled() {
        when(mapper.selectList(any())).thenReturn(List.of(
                // 旧格式: content 是 JSON 字符串节点(带引号)——兼容读取
                record("USER", "\"旧格式纯文本\""),
                // 坏数据: 非法 JSON → 跳过该行, 不抛异常
                record("ASSISTANT", "不是 JSON 的内容"),
                // JSON 对象但没有 text 字段 → 跳过
                record("ASSISTANT", "{\"foo\":1}"),
                // 未知类型 → 跳过
                record("TOOL", "{\"text\":\"工具消息不入记忆\"}"),
                // 正常行照常还原
                record("USER", "{\"text\":\"正常行\"}")));

        List<Message> messages = repository.findByConversationId(CID);

        assertEquals(2, messages.size(), "坏数据/未知类型行必须跳过, 不中断回放");
        assertEquals("旧格式纯文本", messages.get(0).getText());
        assertEquals("正常行", messages.get(1).getText());
    }

    @Test
    void countAndConversationIdsAndDelete() {
        when(mapper.selectCount(any())).thenReturn(7L);
        // Arrays.asList: List.of 不接受 null 元素(这里的 null 恰是待过滤项)
        when(mapper.selectObjs(any())).thenReturn(java.util.Arrays.asList("c1", "c1", null, "c2"));

        assertEquals(7, repository.countByConversationId(CID));
        assertEquals(List.of("c1", "c2"),
                repository.findConversationIds(), "去重且过滤 null");

        repository.deleteByConversationId(CID);
        verify(mapper).delete(any());
    }
}
