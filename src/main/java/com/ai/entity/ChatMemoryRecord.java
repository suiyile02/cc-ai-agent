package com.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话记忆(框架约定表 SPRING_AI_CHAT_MEMORY)。
 * 读写由 DbChatMemoryRepository 完成；content 存 JSON 序列化后的消息文本，
 * type 为消息类型(USER/ASSISTANT/SYSTEM)，timestamp 用于按序回放。
 */
@Getter
@Setter
@Entity
@Table(name = "SPRING_AI_CHAT_MEMORY")
public class ChatMemoryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** conversation_id(=会话 sessionId, UUID, 36 字符) */
    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    /** 消息内容(JSON 序列化文本) */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 消息类型 USER/ASSISTANT/SYSTEM */
    @Column(name = "type", nullable = false, length = 10)
    private String type;

    /** 消息时间戳(排序回放)；timestamp 为保留字, 使用反引号让 Hibernate 按方言自动加引号 */
    @Column(name = "`timestamp`", nullable = false)
    private LocalDateTime timestamp;
}
