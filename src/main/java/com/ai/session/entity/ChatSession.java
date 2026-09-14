package com.ai.session.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话会话(chat_session)。sessionId 即记忆(SPRING_AI_CHAT_MEMORY.conversation_id)与日志业务主键。
 * status: 1-进行中 0-已归档/删除
 */
@Getter
@Setter
@TableName("chat_session")
public class ChatSession {

    /** 会话类型(以字符串 name 持久化到 session_type 列) */
    public enum SessionType {
        /** 仅知识库问答(RAG) */
        RAG,
        /** 仅工具 Agent(不检索知识库) */
        AGENT,
        /** RAG + 工具 */
        HYBRID
    }

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话唯一标识(业务主键, UUID) */
    private String sessionId;

    /** 所属用户 ID */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 会话类型 RAG/AGENT/HYBRID */
    private SessionType sessionType = SessionType.HYBRID;

    /** 1-进行中 0-已归档/删除 */
    private Integer status = 1;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
