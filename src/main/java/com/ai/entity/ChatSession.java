package com.ai.entity;

import com.ai.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 对话会话(chat_session)。
 * sessionId 即记忆(SPRING_AI_CHAT_MEMORY.conversation_id)与对话日志使用的业务主键。
 * status: 1-进行中 0-已归档/删除
 */
@Getter
@Setter
@Entity
@Table(name = "chat_session")
public class ChatSession extends BaseTimeEntity {

    public enum SessionType {
        /** 仅知识库问答(RAG) */
        RAG,
        /** 仅工具 Agent(不检索知识库) */
        AGENT,
        /** RAG + 工具 */
        HYBRID
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识(业务主键, UUID) */
    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    /** 所属用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话标题 */
    @Column(name = "title", length = 200)
    private String title;

    /** 会话类型 RAG/AGENT/HYBRID */
    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 20)
    private SessionType sessionType = SessionType.HYBRID;

    /** 1-进行中 0-已归档/删除 */
    @Column(name = "status", nullable = false)
    private Integer status = 1;
}
