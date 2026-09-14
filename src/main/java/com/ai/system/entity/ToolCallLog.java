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
 * 工具调用日志(tool_call_log)：由 AOP 切面在 Agent 调用 @Tool 方法时记录。
 * status: SUCCESS / FAILED / TIMEOUT
 */
@Getter
@Setter
@TableName("tool_call_log")
public class ToolCallLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话(工具上下文未透传 conversationId 时可为空) */
    private String sessionId;

    /** 所属用户 ID(授权过滤: 管理员或本人可见) */
    private Long userId;

    /** 工具名 */
    private String toolName;

    /** 入参 JSON */
    private String inputParams;

    /** 出参 JSON */
    private String outputResult;

    /** 状态 */
    private String status;

    /** 执行耗时 ms */
    private Integer durationMs;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
