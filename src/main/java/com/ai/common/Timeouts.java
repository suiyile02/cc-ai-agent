package com.ai.common;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 带超时的阻塞调用工具：在虚拟线程上执行任务, 超时后中断并取消,
 * 供查询改写/历史摘要等 LLM 同步调用使用(避免请求线程被挂起的模型调用阻塞)。
 */
public final class Timeouts {

    /** 虚拟线程池: 每任务一线程, 轻量且随中断终止 */
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    /** 并发上限(B4): 防止端点故障时改写/摘要调用无限堆积; 超限立即失败由调用方降级 */
    private static final java.util.concurrent.Semaphore CONCURRENCY = new java.util.concurrent.Semaphore(50);
    private static final java.util.concurrent.atomic.AtomicLong IN_FLIGHT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong PEAK = new java.util.concurrent.atomic.AtomicLong();

    private Timeouts() {
    }

    /**
     * 执行任务并限时返回；超时/失败/中断统一以非受检异常抛出, 由调用方捕获降级。
     * 超时或异常时对底层任务发起 interrupt(虚拟线程阻塞在 IO 上会随之中止)。
     *
     * @param task      待执行任务
     * @param timeoutMs 超时毫秒数
     * @param <T>       返回类型
     * @return 任务结果
     * @throws IllegalStateException 超时/失败/被中断
     */
    public static <T> T call(Supplier<T> task, long timeoutMs) {
        boolean acquired;
        try {
            acquired = CONCURRENCY.tryAcquire(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用被中断", e);
        }
        if (!acquired) {
            throw new IllegalStateException("系统繁忙(LLM 调用并发已达上限), 请稍后重试");
        }
        long inFlight = IN_FLIGHT.incrementAndGet();
        long peak = PEAK.updateAndGet(p -> Math.max(p, inFlight));
        if (inFlight % 20 == 0) {
            java.lang.System.err.println("[Timeouts] LLM 并发调用 inFlight=" + inFlight + ", peak=" + peak);
        }
        try {
            Future<T> future = VIRTUAL.submit(task::get);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IllegalStateException("调用超时(" + timeoutMs + "ms)", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("调用被中断", e);
            } finally {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        } finally {
            IN_FLIGHT.decrementAndGet();
            CONCURRENCY.release();
        }
    }
}
