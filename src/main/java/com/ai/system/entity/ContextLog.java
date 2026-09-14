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
 * 上下文装配可观测日志(context_log)：每次问答记录各段 Token 占用、是否截断、改写结果。
 */
@Getter
@Setter
@TableName("context_log")
public class ContextLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 */
    private String sessionId;

    /** 所属用户 ID(授权过滤: 管理员或本人可见) */
    private Long userId;

    /** 原始用户输入(截断保存) */
    private String userMessage;

    /** 多轮改写后的检索问题(未改写则同原始输入) */
    private String rewrittenQuery;

    /** 意图判定: KB/GENERAL */
    private String intentMode;

    /** system 提示词(不含 RAG)Token */
    private Integer systemTokens = 0;

    /** 历史段(摘要 + 最近消息)Token */
    private Integer historyTokens = 0;

    /** 其中摘要段 Token */
    private Integer summaryTokens = 0;

    /** RAG 检索上下文 Token */
    private Integer ragTokens = 0;

    /** 当前用户输入 Token */
    private Integer userTokens = 0;

    /** 合计 Token */
    private Integer totalTokens = 0;

    /** 模型上下文窗口上限 */
    private Integer modelMaxTokens;

    /** 注入的历史消息条数(不含摘要) */
    private Integer historyMessages = 0;

    /** 是否发生窗口/预算截断 */
    private Boolean truncated = false;

    /** 装配耗时 ms */
    private Integer durationMs;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
