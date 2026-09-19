package com.ai.common;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级告警节流器：同一处降级在时间窗口内只放行一条 WARN，并把窗口内被抑制的条数汇总进下一条。
 *
 * <p>为什么需要：旁路组件（Redis 等）故障时，降级路径<b>每个请求</b>都会触发（一次对话可命中多处），
 * 不打日志则"故障静默"（运维只表现为缓存突然全不命中，查不到原因），全打则日志刷屏并拖慢请求。
 * 折成"60 秒一条 + 抑制计数"既看得见又打不爆。
 *
 * <p>线程安全：用 CAS 抢占放行权，同一窗口并发下只会有一条日志落地。
 *
 * <p>用法（配合 Lombok {@code @Slf4j}，以字段初始化器创建，不进构造器参数）：
 * <pre>{@code private final WarnThrottle degraded = WarnThrottle.of(log);}</pre>
 */
public final class WarnThrottle {

    /** 默认放行窗口：60 秒 */
    private static final long DEFAULT_WINDOW_MS = 60_000L;

    private final Logger log;
    private final long windowMs;

    /** 上次放行时间戳(ms), 0=从未放行 */
    private final AtomicLong lastPassMs = new AtomicLong(0);
    /** 本窗口内被抑制的条数 */
    private final AtomicLong suppressed = new AtomicLong(0);

    private WarnThrottle(Logger log, long windowMs) {
        this.log = log;
        this.windowMs = windowMs;
    }

    /**
     * 创建默认 60 秒窗口的节流器。
     *
     * @param log 目标类的 Logger（Lombok {@code @Slf4j} 生成的 {@code log}）
     * @return 节流器
     */
    public static WarnThrottle of(Logger log) {
        return new WarnThrottle(log, DEFAULT_WINDOW_MS);
    }

    /**
     * 创建指定窗口的节流器。
     *
     * @param log      目标类的 Logger
     * @param windowMs 放行窗口毫秒
     * @return 节流器
     */
    public static WarnThrottle of(Logger log, long windowMs) {
        return new WarnThrottle(log, windowMs);
    }

    /**
     * 记录一条降级告警：窗口内只放行第一条，其余计数。
     *
     * @param format slf4j 日志模板（放行时自动追加"已抑制 N 条同类降级"）
     * @param args   模板参数
     */
    public void warn(String format, Object... args) {
        long now = System.currentTimeMillis();
        long last = lastPassMs.get();
        if (now - last >= windowMs && lastPassMs.compareAndSet(last, now)) {
            long skipped = suppressed.getAndSet(0);
            if (skipped > 0) {
                log.warn(format + " (窗口内已抑制 {} 条同类降级)", append(args, skipped));
            } else {
                log.warn(format, args);
            }
            return;
        }
        suppressed.incrementAndGet();
    }

    /**
     * 把汇总参数追加到调用方参数表尾部。
     *
     * @param args    调用方参数(可为空数组)
     * @param skipped 被抑制条数
     * @return 新参数数组
     */
    private static Object[] append(Object[] args, long skipped) {
        Object[] out = new Object[args.length + 1];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = skipped;
        return out;
    }
}
