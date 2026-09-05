package com.ai.agent;

import com.ai.entity.ToolCallLog;
import com.ai.service.ToolCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具调用日志切面(需求 4.4)：对 @Tool 方法环绕记录入参/出参/耗时/状态。
 *
 * <p>说明：会话 ID 未透传到工具上下文时置空，可在后续通过 ToolContext 传递补全。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallLogAspect {

    private final ToolCallLogService toolCallLogService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(tool)")
    public Object around(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = tool.name() == null || tool.name().isBlank()
                ? pjp.getSignature().getName() : tool.name();
        String inputParams = toJson(pjp.getArgs());
        String sessionId = null; // 工具上下文暂未透传 conversationId

        ToolCallLog entry = new ToolCallLog();
        entry.setToolName(toolName);
        entry.setInputParams(inputParams);
        entry.setSessionId(sessionId);

        try {
            Object result = pjp.proceed();
            entry.setStatus("SUCCESS");
            entry.setOutputResult(toJson(result));
            return result;
        } catch (Throwable e) {
            entry.setStatus("FAILED");
            entry.setErrorMessage(truncate(e.toString(), 1000));
            throw e;
        } finally {
            entry.setDurationMs((int) (System.currentTimeMillis() - start));
            try {
                toolCallLogService.save(entry);
            } catch (Exception ex) {
                log.warn("工具调用日志保存失败(忽略): {}", ex.getMessage());
            }
        }
    }

    private String toJson(Object value) {
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
