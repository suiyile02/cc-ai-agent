package com.ai.session.entity;

import com.ai.session.SessionType;
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
 * status 取值见 {@link #STATUS_ACTIVE} / {@link #STATUS_CLOSED}(0 为归档与软删共用的终态)。
 */
@Getter
@Setter
@TableName("chat_session")
public class ChatSession {

    /** status=1 进行中：唯一可对话、可被读取的状态 */
    public static final int STATUS_ACTIVE = 1;
    /** status=0 已归档/已删除：软删终态，列表不出现、详情不可读、不可再对话 */
    public static final int STATUS_CLOSED = 0;

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

    /** 会话状态：1 进行中 / 0 已归档或已删除，取值见 {@link #STATUS_ACTIVE}、{@link #STATUS_CLOSED} */
    private Integer status = STATUS_ACTIVE;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
