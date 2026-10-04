package com.ai.memory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话原始轨迹留档(chat_memory_raw, append-only)。
 * 滚动摘要裁剪只收缩工作表 SPRING_AI_CHAT_MEMORY, 本表留存完整消息原文与每次压缩事件(SUMMARY 行),
 * 供"模型当时看到了什么"的回放审计与摘要策略离线重算；行随会话删除清理, 无独立保留期。
 */
@Getter
@Setter
@TableName("chat_memory_raw")
public class ChatMemoryRaw {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** conversation_id(=会话 sessionId, UUID, 36 字符) */
    private String conversationId;

    /** 会话内单调递增序号(与 created_at 共同保序) */
    private Long seq;

    /** 行类型: USER/ASSISTANT/SUMMARY */
    private String role;

    /** 原文(SUMMARY 行为当次摘要全文) */
    private String content;

    /** 仅 SUMMARY 行: 第几次滚动摘要压缩(1 起) */
    private Integer batch;

    /** 入档时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
