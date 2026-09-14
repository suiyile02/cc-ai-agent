package com.ai.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话记忆(框架约定表 SPRING_AI_CHAT_MEMORY)。
 * 读写由 DbChatMemoryRepository 完成；content 存 JSON 序列化消息文本，
 * type 为消息类型(USER/ASSISTANT/SYSTEM)，timestamp 用于按序回放。
 */
@Getter
@Setter
@TableName("SPRING_AI_CHAT_MEMORY")
public class ChatMemoryRecord {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** conversation_id(=会话 sessionId, UUID, 36 字符) */
    private String conversationId;

    /** 消息内容(JSON 序列化文本) */
    private String content;

    /** 消息类型 USER/ASSISTANT/SYSTEM */
    private String type;

    /** 消息时间戳(timestamp 为保留字, 反引号让 MySQL/H2 均按标识符解析) */
    @TableField(value = "`timestamp`")
    private LocalDateTime timestamp;
}
