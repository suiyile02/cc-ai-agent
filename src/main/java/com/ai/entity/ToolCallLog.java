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
 * 工具调用日志(tool_call_log)：由 AOP 切面在 Agent 调用 @Tool 方法时记录。
 * status: SUCCESS / FAILED / TIMEOUT
 */
@Getter
@Setter
@Entity
@Table(name = "tool_call_log")
public class ToolCallLog extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话(工具上下文未透传 conversationId 时可为空) */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    /** 入参 JSON */
    @Column(name = "input_params", columnDefinition = "TEXT", nullable = false)
    private String inputParams;

    /** 出参 JSON */
    @Column(name = "output_result", columnDefinition = "TEXT")
    private String outputResult;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
