package com.ai.common;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步线程池的 MDC 传播装饰器：任务提交时把提交线程的 MDC(含 traceId)快照复制一份,
 * 在执行线程内恢复, 执行完清理——让 {@code @Async} 审计落库/文档入库等异步任务的日志
 * 也带上请求的 traceId, 不在异步段断链。
 *
 * <p>用法: 在线程池 Bean 上 {@code executor.setTaskDecorator(new MdcTaskDecorator())}。
 */
public class MdcTaskDecorator implements TaskDecorator {

    /**
     * 包装任务：捕获提交线程的 MDC 快照, 执行前恢复、执行后清理。
     *
     * @param runnable 原始任务
     * @return 携带 MDC 快照的包装任务
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap(); // 提交线程的快照
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear(); // 线程池复用防串号
            }
        };
    }
}
