package com.ai.memory;

import com.ai.entity.ChatMemoryRecord;
import com.ai.repository.ChatMemoryRecordRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于数据库(SPRING_AI_CHAT_MEMORY)的 {@link ChatMemoryRepository} 实现(由 ChatConfig 注册为 Bean)。
 *
 * <p>对齐需求建表脚本第 1 张框架约定表：
 * {@code conversation_id / content(JSON序列化) / type(USER|ASSISTANT|SYSTEM|TOOL) / timestamp}。
 * 仅持久化纯文本的 USER/ASSISTANT/SYSTEM 消息，保证多轮记忆跨调用稳定回放
 * (工具内部消息不持久化, 避免回放失败)。
 */
public class DbChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public DbChatMemoryRepository(ChatMemoryRecordRepository recordRepository, ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findConversationIds() {
        return recordRepository.findDistinctConversationIds();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> findByConversationId(String conversationId) {
        List<ChatMemoryRecord> records =
                recordRepository.findByConversationIdOrderByTimestampAscIdAsc(conversationId);
        List<Message> messages = new ArrayList<>();
        for (ChatMemoryRecord record : records) {
            Message msg = toMessage(record);
            if (msg != null) {
                messages.add(msg);
            }
        }
        return messages;
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        recordRepository.deleteByConversationId(conversationId);
        LocalDateTime now = LocalDateTime.now();
        int seq = 0;
        for (Message message : messages) {
            MessageType type = message.getMessageType();
            if (type != MessageType.USER && type != MessageType.ASSISTANT && type != MessageType.SYSTEM) {
                continue; // 工具内部消息不持久化, 见类注释
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
            recordRepository.save(record);
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        recordRepository.deleteByConversationId(conversationId);
    }

    /** content 列存储 JSON 序列化文本, 与建表注释"消息内容（JSON序列化）"一致 */
    private String toJson(String text) {
        try {
            return objectMapper.writeValueAsString(new JsonText(text));
        } catch (Exception e) {
            return "{\"text\":" + objectMapper.valueToTree(text).toString() + "}";
        }
    }

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
            // 兼容旧格式(直接文本)
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private record JsonText(String text) {
    }
}
