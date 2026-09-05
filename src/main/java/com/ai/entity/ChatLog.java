package com.ai.entity;

import com.ai.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 对话日志(chat_log)：每次问答留痕，供系统管理模块查询。
 */
@Getter
@Setter
@Entity
@Table(name = "chat_log")
public class ChatLog extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_message", columnDefinition = "TEXT", nullable = false)
    private String userMessage;

    @Column(name = "assistant_reply", columnDefinition = "TEXT")
    private String assistantReply;

    /** 引用来源(JSON) */
    @Column(name = "sources", columnDefinition = "TEXT")
    private String sources;

    /** 工具调用记录(JSON) */
    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    /** 使用的模型 */
    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName;

    /** 总 Token 消耗(视模型返回情况) */
    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 总耗时 ms */
    @Column(name = "duration_ms")
    private Integer durationMs;
}
