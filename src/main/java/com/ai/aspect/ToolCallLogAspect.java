package com.ai.aspect;
import com.ai.system.service.ToolCallLogService;

import com.ai.common.SensitiveDataMasker;
import com.ai.common.Strings;
import com.ai.system.entity.ToolCallLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用日志切面(需求 4.4)：对 @Tool 方法环绕记录入参/出参/耗时/状态到 tool_call_log。
 *
 * <p>顺带维护 toolContext 里的本轮工具调用计数(键 {@code toolCalls})——对话收尾阶段据此
 * 决定是否跳过语义缓存写入; 并执行**单轮工具调用次数上限**(B2, {@code app.chat.max-tool-calls-per-turn},
 * 超限抛 {@code TOOL_CALL_LIMIT} 由 Spring AI 回传模型令其收尾作答, 不真正执行工具)。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallLogAspect {

    private final ToolCallLogService toolCallLogService;
    private final ObjectMapper objectMapper;
    private final com.ai.config.AppProperties appProperties;

    /**
     * 环绕增强：执行前记录入参, 成功后记录出参与状态 SUCCESS, 异常记录 FAILED 并继续抛出。
     * 超上限时跳过工具执行、直接抛 {@code TOOL_CALL_LIMIT}。
     *
     * @param pjp  连接点(被拦截的 @Tool 方法)
     * @param tool 方法上的 @Tool 注解(可取工具名/描述)
     * @return 目标方法返回值(透传)
     * @throws Throwable 目标方法异常(透传); 超上限抛 BusinessException(TOOL_CALL_LIMIT)
     */
    @Around("@annotation(tool)")
    public Object around(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = tool.name() == null || tool.name().isBlank()
                ? pjp.getSignature().getName() : tool.name();
        // 工具入参/出参可能携带用户真实手机号/邮箱, 落库前脱敏(P2-3)
        String inputParams = SensitiveDataMasker.mask(toJson(stripToolContext(pjp.getArgs())));
        ToolContext toolContext = findToolContext(pjp.getArgs());

        ToolCallLog entry = new ToolCallLog();
        entry.setToolName(toolName);
        entry.setInputParams(inputParams);
        // 会话与用户由 ChatService 经 toolContext 透传(未透传时保持为空)
        if (toolContext != null && toolContext.getContext() != null) {
            Object sessionId = toolContext.getContext().get("sessionId");
            Object userId = toolContext.getContext().get("userId");
            if (sessionId instanceof String sid && !sid.isBlank()) {
                entry.setSessionId(sid);
            }
            if (userId instanceof Number uid) {
                entry.setUserId(uid.longValue());
            }
        }

        try {
            // 本轮工具调用计数 + 次数上限拦截(B2): 超限不执行工具, 直接抛错回传模型
            // (计数同时供收尾判定"回答含实时业务数据"→禁止写语义缓存)
            if (toolContext != null && toolContext.getContext() != null
                    && toolContext.getContext().get("toolCalls") instanceof AtomicInteger counter) {
                int max = appProperties.getChat().getMaxToolCallsPerTurn();
                if (max > 0 && counter.incrementAndGet() > max) {
                    throw new com.ai.common.BusinessException(
                            com.ai.common.ErrorCode.TOOL_CALL_LIMIT,
                            "单轮工具调用次数已达上限(" + max + "), 已终止本轮工具调用");
                }
            }

            Object result = pjp.proceed();
            entry.setStatus("SUCCESS");
            entry.setOutputResult(SensitiveDataMasker.mask(toJson(result)));
            return result;
        } catch (Throwable e) {
            entry.setStatus("FAILED");
            entry.setErrorMessage(Strings.truncate(e.toString(), 1000));
            throw e;
        } finally {
            entry.setDurationMs((int) (System.currentTimeMillis() - start));
            io.micrometer.core.instrument.Metrics.counter(
                    com.ai.observability.ChatMetrics.TOOL_CALLS,
                    "tool", toolName, "status", entry.getStatus() == null ? "UNKNOWN" : entry.getStatus()
            ).increment();
            try {
                toolCallLogService.save(entry);
            } catch (Exception ex) {
                log.warn("工具调用日志保存失败(忽略): {}", ex.getMessage());
            }
        }
    }

    /**
     * 从工具方法参数中提取 ToolContext(由 ChatService 经 toolContext 透传)。
     *
     * @param args 工具方法参数
     * @return ToolContext, 不存在返回 null
     */
    private ToolContext findToolContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ToolContext tc) {
                return tc;
            }
        }
        return null;
    }

    /**
     * 序列化入参前剔除 ToolContext(上下文不是业务入参, 不落库)。
     *
     * @param args 工具方法参数
     * @return 剔除 ToolContext 后的参数数组
     */
    private Object[] stripToolContext(Object[] args) {
        return java.util.Arrays.stream(args)
                .filter(a -> !(a instanceof ToolContext))
                .toArray();
    }

    /**
     * 对象 → JSON 文本(字符串直接返回, 其它对象 Jackson 序列化, 失败退化为 toString)。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
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

}
