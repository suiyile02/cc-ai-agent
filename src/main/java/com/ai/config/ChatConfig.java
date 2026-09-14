package com.ai.config;

import com.ai.memory.DbChatMemoryRepository;
import com.ai.memory.mapper.ChatMemoryRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆 + ChatClient 装配。
 *
 * <p>记忆链路：SPRING_AI_CHAT_MEMORY 表 ← DbChatMemoryRepository ← MessageWindowChatMemory。
 * 历史的读取/写回与滚动摘要由上下文管线 {@code ConversationMemoryService} 自管(统一 Token 预算),
 * 因此 ChatClient 不再挂 {@code MessageChatMemoryAdvisor}, 避免“管线裁剪 + advisor 再注入”双重历史。
 *
 * <p>ChatClient 在 ChatModel 可用时才创建(未配置 API Key 时应用仍可启动, 对话接口友好降级)。
 */
@Configuration
public class ChatConfig {

    /**
     * 基于 DB 的 ChatMemoryRepository(写 SPRING_AI_CHAT_MEMORY)。
     *
     * @param recordMapper 记忆表 Mapper
     * @param objectMapper JSON 序列化(存储消息文本)
     * @return ChatMemoryRepository 实现
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public ChatMemoryRepository chatMemoryRepository(ChatMemoryRecordMapper recordMapper,
            ObjectMapper objectMapper) {
        return new DbChatMemoryRepository(recordMapper, objectMapper);
    }

    /**
     * 带消息窗口的对话记忆(回放最近 30 条)。
     *
     * @param chatMemoryRepository 记忆持久化实现
     * @return ChatMemory
     */
    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(30)
                .build();
    }

}
