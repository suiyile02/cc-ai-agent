package com.ai.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WarnThrottle} 单元测试：窗口内只放行一条、跨窗口再放行并汇总抑制条数、并发只放行一条。
 * 日志就是该类的唯一输出面，故断言直接读 logback 事件。
 */
class WarnThrottleTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("com.ai.test.WarnThrottle");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void onlyFirstWarnPassesWithinWindow() {
        WarnThrottle throttle = WarnThrottle.of(logger, 60_000);

        throttle.warn("redis down: {}", "boom");
        throttle.warn("redis down: {}", "boom");
        throttle.warn("redis down: {}", "boom");

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "同窗口内只应放行一条");
        assertEquals("redis down: boom", events.get(0).getFormattedMessage());
    }

    @Test
    void nextWindowReportsSuppressedCount() throws InterruptedException {
        WarnThrottle throttle = WarnThrottle.of(logger, 20);
        throttle.warn("降级: {}", "a");
        throttle.warn("降级: {}", "b");
        Thread.sleep(40);

        throttle.warn("降级: {}", "c");

        assertEquals(2, appender.list.size());
        String second = appender.list.get(1).getFormattedMessage();
        assertTrue(second.startsWith("降级: c"), second);
        assertTrue(second.contains("已抑制 1 条"), "第二条应汇总被抑制的条数: " + second);
    }

    @Test
    void concurrentCallsEmitSingleLine() throws InterruptedException {
        WarnThrottle throttle = WarnThrottle.of(logger, 60_000);
        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throttle.warn("并发降级");
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, appender.list.size(), "CAS 放行权下并发只应有一条日志");
    }
}
