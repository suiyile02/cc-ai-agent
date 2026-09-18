package com.ai.aspect;

import com.ai.system.entity.ToolCallLog;
import com.ai.system.service.ToolCallLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolCallLogAspect} 单元测试：切面对 toolContext 里"本轮工具调用计数"的自增——
 * 该计数是对话收尾阶段禁止工具轮次写语义缓存的唯一数据源, 断了就会退回跨用户串答案的缺陷。
 */
class ToolCallLogAspectTest {

    private final ToolCallLogService logService = mock(ToolCallLogService.class);
    private final ToolCallLogAspect aspect = new ToolCallLogAspect(logService, new ObjectMapper());

    /** 构造一个 @Tool 注解替身(切面只读 name) */
    private Tool toolAnnotation() {
        Tool tool = mock(Tool.class);
        lenient().when(tool.name()).thenReturn("queryOrder");
        return tool;
    }

    /**
     * 构造连接点：工具入参 + 携带指定上下文的 ToolContext(由 ChatService 经 toolContext 透传)。
     *
     * @param context   工具上下文内容
     * @param returnValue 目标方法返回值
     * @return 连接点 mock
     */
    private ProceedingJoinPoint joinPoint(Map<String, Object> context, Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        lenient().when(pjp.getArgs()).thenReturn(
                new Object[]{"SO20260101001", new ToolContext(context)});
        lenient().when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    @Test
    void incrementsToolCallCounterFromToolContext() throws Throwable {
        AtomicInteger counter = new AtomicInteger();
        ProceedingJoinPoint pjp = joinPoint(
                Map.of("sessionId", "s1", "userId", 7L, "toolCalls", counter), "已发货");

        assertEquals("已发货", aspect.around(pjp, toolAnnotation()));

        assertEquals(1, counter.get(), "每次工具调用应自增一次");
        ArgumentCaptor<ToolCallLog> saved = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logService).save(saved.capture());
        assertEquals("SUCCESS", saved.getValue().getStatus());
        assertEquals("s1", saved.getValue().getSessionId());
        assertEquals(7L, saved.getValue().getUserId());
    }

    @Test
    void failedToolCallStillCountsAndMarksFailed() throws Throwable {
        AtomicInteger counter = new AtomicInteger();
        ProceedingJoinPoint pjp = joinPoint(
                Map.of("sessionId", "s1", "toolCalls", counter), null);
        when(pjp.proceed()).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> aspect.around(pjp, toolAnnotation()));

        assertEquals(1, counter.get(), "工具失败同样说明答案掺入了工具轮次, 必须计数");
        ArgumentCaptor<ToolCallLog> saved = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logService).save(saved.capture());
        assertEquals("FAILED", saved.getValue().getStatus());
    }

    @Test
    void absentCounterOrToolContextIsTolerated() throws Throwable {
        ProceedingJoinPoint withoutCounter = joinPoint(Map.of("sessionId", "s1"), "结果");

        assertEquals("结果", aspect.around(withoutCounter, toolAnnotation()));

        ProceedingJoinPoint plain = mock(ProceedingJoinPoint.class);
        when(plain.getArgs()).thenReturn(new Object[]{"SO001"});
        when(plain.proceed()).thenReturn("结果2");
        assertEquals("结果2", aspect.around(plain, toolAnnotation()));
    }

    @Test
    void multipleCallsAccumulate() throws Throwable {
        AtomicInteger counter = new AtomicInteger();
        Map<String, Object> context = Map.of("sessionId", "s1", "toolCalls", counter);

        aspect.around(joinPoint(context, "a"), toolAnnotation());
        aspect.around(joinPoint(context, "b"), toolAnnotation());

        assertEquals(2, counter.get());
    }
}
