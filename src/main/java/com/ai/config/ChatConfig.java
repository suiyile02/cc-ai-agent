package com.ai.config;

import com.ai.memory.DbChatMemoryRepository;
import com.ai.repository.ChatMemoryRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆 + ChatClient 装配。
 *
 * <p>记忆链路：SPRING_AI_CHAT_MEMORY 表 <- DbChatMemoryRepository <- MessageWindowChatMemory
 * <- MessageChatMemoryAdvisor(每次对话按 conversationId=sessionId 读写历史)。
 *
 * <p>ChatClient 在 ChatModel 可用时才创建(未配置 API Key 时应用仍可启动, 对话接口友好降级)。
 */
@Configuration
public class ChatConfig {

    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public ChatMemoryRepository chatMemoryRepository(ChatMemoryRecordRepository recordRepository,
            ObjectMapper objectMapper) {
        return new DbChatMemoryRepository(recordRepository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(30)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(Advisor.class)
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient chatClient(ChatModel chatModel, Advisor messageChatMemoryAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(messageChatMemoryAdvisor)
                .build();
    }
}
