package com.ai.memory;

import com.ai.memory.entity.ChatMemoryRecord;
import com.ai.memory.mapper.ChatMemoryRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于数据库(SPRING_AI_CHAT_MEMORY)的 {@link ChatMemoryRepository} 实现。
 *
 * <p>对齐建表脚本第 1 张框架约定表：
 * conversation_id / content(JSON序列化) / type(USER|ASSISTANT|SYSTEM) / timestamp。
 * 仅持久化纯文本消息, 保证多轮记忆跨调用稳定回放(工具内部消息不持久化)。
 */
@Component
public class DbChatMemoryRepository implements ChatMemoryRepository, ChatMemoryAppender,
        ChatMemoryCounter {

    private final ChatMemoryRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    /**
     * 构造记忆仓储。
     *
     * @param recordMapper 记忆表 Mapper
     * @param objectMapper JSON 序列化器
     */
    public DbChatMemoryRepository(ChatMemoryRecordMapper recordMapper, ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回所有出现过消息的会话 ID(去重)。
     *
     * @return 会话 ID 列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> findConversationIds() {
        QueryWrapper<ChatMemoryRecord> qw = new QueryWrapper<>();
        qw.select("DISTINCT conversation_id").isNotNull("conversation_id");
        List<Object> values = recordMapper.selectObjs(qw);
        Set<String> ids = new LinkedHashSet<>();
        for (Object v : values) {
            if (v != null) {
                ids.add(String.valueOf(v));
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 按会话读取历史消息(按 timestamp 与 id 升序回放)。
     *
     * @param conversationId 会话 ID
     * @return 可回放消息列表(仅纯文本 USER/ASSISTANT/SYSTEM)
     */
    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(String conversationId) {
        List<ChatMemoryRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<ChatMemoryRecord>()
                        .eq(ChatMemoryRecord::getConversationId, conversationId)
                        .orderByAsc(ChatMemoryRecord::getTimestamp)
                        .orderByAsc(ChatMemoryRecord::getId));
        List<Message> messages = new ArrayList<>();
        for (ChatMemoryRecord record : records) {
            Message msg = toMessage(record);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }

    /**
     * 全量替换某会话历史(先删后插)。
     *
     * @param conversationId 会话 ID
     * @param messages       全部消息(工具内部消息被过滤)
     */
    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        recordMapper.delete(new LambdaQueryWrapper<ChatMemoryRecord>()
                .eq(ChatMemoryRecord::getConversationId, conversationId));
        LocalDateTime now = LocalDateTime.now();
        int seq = 0;
        for (Message message : messages) {
            MessageType type = message.getMessageType();
            if (type != MessageType.USER && type != MessageType.ASSISTANT
                    && type != MessageType.SYSTEM) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            ChatMemoryRecord record = new ChatMemoryRecord();
            record.setConversationId(conversationId);
            record.setType(type.name());
            record.setContent(toJson(text));
            record.setTimestamp(now.plusSeconds(seq++));
            recordMapper.insert(record);
        }
    }

    /**
     * 追加新消息(append-only): 只 INSERT 新增行, 不删除/重写既有历史,
     * 时间戳使用真实当前时间(替代 saveAll 的 now+seq 伪时间)。
     *
     * @param conversationId 会话 ID
     * @param newMessages    新增消息
     */
    @Override
    public void append(String conversationId, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }
        for (Message message : newMessages) {
            MessageType type = message.getMessageType();
            if (type != MessageType.USER && type != MessageType.ASSISTANT
                    && type != MessageType.SYSTEM) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            ChatMemoryRecord record = new ChatMemoryRecord();
            record.setConversationId(conversationId);
            record.setType(type.name());
            record.setContent(toJson(text));
            record.setTimestamp(LocalDateTime.now());
            recordMapper.insert(record);
        }
    }

    /**
     * 统计某会话的历史消息条数(数据库 COUNT(*), 不加载消息内容)。
     *
     * @param conversationId 会话 ID
     * @return 消息条数
     */
    @Override
    public long countByConversationId(String conversationId) {
        return recordMapper.selectCount(new LambdaQueryWrapper<ChatMemoryRecord>()
                .eq(ChatMemoryRecord::getConversationId, conversationId));
    }

    /**
     * 删除某会话全部记忆(删除会话时调用)。
     *
     * @param conversationId 会话 ID
     */
    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        recordMapper.delete(new LambdaQueryWrapper<ChatMemoryRecord>()
                .eq(ChatMemoryRecord::getConversationId, conversationId));
    }

    /**
     * content 列 JSON 序列化文本, 与建表注释“消息内容（JSON序列化）”一致。
     *
     * @param text 消息文本
     * @return JSON 字符串 {"text":"..."}
     */
    private String toJson(String text) {
        try {
            return objectMapper.writeValueAsString(new JsonText(text));
        } catch (Exception e) {
            return "{\"text\":" + objectMapper.valueToTree(text).toString() + "}";
        }
    }

    /**
     * 记录 → 消息对象(按 type), 类型不支持返回 null。
     *
     * @param record 记忆记录
     * @return Message 或 null
     */
    private Message toMessage(ChatMemoryRecord record) {
        String content = readText(record.getContent());
        if (content == null) {
            return null;
        }
        return switch (record.getType() == null ? "" : record.getType()) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> new AssistantMessage(content);
            case "SYSTEM" -> new SystemMessage(content);
            default -> null;
        };
    }

    /**
     * 解析 content JSON(兼容直接文本旧数据)。
     *
     * @param json JSON 或纯文本
     * @return 文本, 解析失败返回 null
     */
    private String readText(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode text = node.get("text");
            if (text != null && text.isTextual()) {
                return text.asText();
            }
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** content 列 JSON 载体 */
    private record JsonText(String text) {
    }
}
