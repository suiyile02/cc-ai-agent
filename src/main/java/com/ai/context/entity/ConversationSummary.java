package com.ai.context.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话历史滚动摘要(conversation_summary)。
 *
 * <p>上下文管线在历史消息超过阈值时, 把较早的对话压缩为摘要并裁剪原始记录,
 * 摘要作为一条 SYSTEM 消息注入后续对话, 避免超出模型上下文窗口。
 */
@Getter
@Setter
@TableName("conversation_summary")
public class ConversationSummary {

    /** 会话 ID(=sessionId, 主键, 手动写入) */
    @TableId(type = IdType.INPUT)
    private String conversationId;

    /** 滚动摘要文本 */
    private String summaryText;

    /** 已摘要截止的消息时间戳(信息用途) */
    private LocalDateTime summarizedUntil;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
