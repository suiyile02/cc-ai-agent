package com.ai.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话日志(chat_log)：每次问答留痕, 供系统管理模块查询。
 */
@Getter
@Setter
@TableName("chat_log")
public class ChatLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 用户提问 */
    private String userMessage;

    /** AI 回答 */
    private String assistantReply;

    /** 引用来源(JSON) */
    private String sources;

    /** 工具调用记录(JSON) */
    private String toolCalls;

    /** 使用的模型 */
    private String modelName;

    /** 总 Token 消耗(视模型返回情况) */
    private Integer totalTokens;

    /** 总耗时 ms */
    private Integer durationMs;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
