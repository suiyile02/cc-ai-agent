package com.ai.aspect;
import com.ai.system.service.ToolCallLogService;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.SensitiveDataMasker;
import com.ai.common.Strings;
import com.ai.config.AppProperties;
import com.ai.observability.ChatMetrics;
import com.ai.system.entity.ToolCallLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用日志切面(需求 4.4)：对 @Tool 方法环绕记录入参/出参/耗时/状态到 tool_call_log。
 *
 * <p>顺带维护 toolContext 里的本轮工具调用计数(键 {@code toolCalls})——对话收尾阶段据此
 * 决定是否跳过语义缓存写入; 并执行**单轮工具调用次数上限**(B2, {@code app.chat.max-tool-calls-per-turn},
 * 超限抛 {@code TOOL_CALL_LIMIT} 由 Spring AI 回传模型令其收尾作答, 不真正执行工具)。
 *
 * <p>工具结果驱逐: 单结果超过 {@code app.chat.tool-result-evict-chars} 或单轮累计超过
 * {@code tool-result-turn-max-chars} 时, 回给模型的不再是全文而是"头尾预览+tool_call_log 存档指针";
 * 完整结果(脱敏后)仍照常落库——驱逐只影响模型输入, 不影响审计。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolCallLogAspect {

    private final ToolCallLogService toolCallLogService;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    /**
     * 环绕增强：执行前记录入参, 成功后记录出参与状态 SUCCESS, 异常记录 FAILED 并继续抛出。
     * 超上限时跳过工具执行、直接抛 {@code TOOL_CALL_LIMIT}; 成功路径先落库(拿行 id 供驱逐指针引用)。
     *
     * @param pjp  连接点(被拦截的 @Tool 方法)
     * @param tool 方法上的 @Tool 注解(可取工具名/描述)
     * @return 目标方法返回值(透传); 结果被驱逐时返回"预览+存档指针"文本
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

        boolean persisted = false;
        try {
            // 本轮工具调用计数 + 次数上限拦截(B2): 超限不执行工具, 直接抛错回传模型
            // (计数同时供收尾判定"回答含实时业务数据"→禁止写语义缓存)
            if (toolContext != null && toolContext.getContext() != null
                    && toolContext.getContext().get("toolCalls") instanceof AtomicInteger counter) {
                int max = appProperties.getChat().getMaxToolCallsPerTurn();
                if (max > 0 && counter.incrementAndGet() > max) {
                    throw new BusinessException(
                            ErrorCode.TOOL_CALL_LIMIT,
                            "单轮工具调用次数已达上限(" + max + "), 已终止本轮工具调用");
                }
            }

            Object result = pjp.proceed();
            entry.setStatus("SUCCESS");
            String full = SensitiveDataMasker.mask(toJson(result));
            entry.setOutputResult(full);
            if (needEvict(full, toolContext)) {
                // 先落库拿行 id(驱逐指针要引用存档位置), 再把"预览+指针"回给模型
                persisted = persist(entry);
                return evictedNotice(full, entry.getId(), toolContext);
            }
            accountTurnChars(full.length(), toolContext);
            persisted = persist(entry);
            return result;
        } catch (Throwable e) {
            entry.setStatus("FAILED");
            entry.setErrorMessage(Strings.truncate(e.toString(), 1000));
            throw e;
        } finally {
            entry.setDurationMs((int) (System.currentTimeMillis() - start));
            Metrics.counter(
                    ChatMetrics.TOOL_CALLS,
                    "tool", toolName, "status", entry.getStatus() == null ? "UNKNOWN" : entry.getStatus()
            ).increment();
            if (!persisted) {
                // 失败路径兜底落库(成功路径已在 try 内落过)
                persist(entry);
            }
        }
    }

    /**
     * 驱逐判定: 单结果超 evict-chars, 或累计后超单轮 turn-max-chars。
     *
     * @param full        脱敏后的完整结果文本
     * @param toolContext 工具上下文(取单轮字符量累计器; 缺失时只按单结果判定)
     * @return true=需要驱逐
     */
    private boolean needEvict(String full, ToolContext toolContext) {
        int evictChars = appProperties.getChat().getToolResultEvictChars();
        if (evictChars > 0 && full.length() > evictChars) {
            return true;
        }
        int turnMax = appProperties.getChat().getToolResultTurnMaxChars();
        return turnMax > 0 && turnChars(toolContext) != null
                && turnChars(toolContext).get() + full.length() > turnMax;
    }

    /**
     * 构造"头尾预览+存档指针"的驱逐文案, 并把模型实际可见的字符量计入单轮预算。
     *
     * @param full        完整结果文本
     * @param logId       tool_call_log 行 id
     * @param toolContext 工具上下文
     * @return 回给模型的替代文本
     */
    private String evictedNotice(String full, Long logId, ToolContext toolContext) {
        String archiveRef = logId == null
                ? "已存档于调用日志"
                : "完整结果已存档(tool_call_log#" + logId + ")";
        String notice = "[工具结果过大已截断, 共 " + full.length() + " 字。开头: "
                + Strings.truncate(full, 500)
                + " …… 结尾: " + tail(full, 300)
                + "。" + archiveRef + ", 如需其余信息请用更具体的参数重新查询]";
        accountTurnChars(notice.length(), toolContext);
        return notice;
    }

    /**
     * 落库一次; 失败仅 WARN——审计缺失不得影响对话。
     *
     * @param entry 待落库记录
     * @return true=落库成功
     */
    private boolean persist(ToolCallLog entry) {
        try {
            toolCallLogService.save(entry);
            return true;
        } catch (Exception ex) {
            log.warn("工具调用日志保存失败(忽略): {}", ex.getMessage());
            return false;
        }
    }

    /** 累加本轮回传模型的字符量(驱逐轮计入预览+指针的长度, 而非全文长度) */
    private void accountTurnChars(int length, ToolContext toolContext) {
        AtomicInteger acc = turnChars(toolContext);
        if (acc != null) {
            acc.addAndGet(length);
        }
    }

    /** 从 toolContext 取单轮字符量累计器(ChatService 注入; 未注入返回 null, 单轮护栏自动关闭) */
    private AtomicInteger turnChars(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        return toolContext.getContext().get("toolResultChars") instanceof AtomicInteger acc ? acc : null;
    }

    /** 取文本尾部(驱逐预览用) */
    private String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
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
        return Arrays.stream(args)
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
