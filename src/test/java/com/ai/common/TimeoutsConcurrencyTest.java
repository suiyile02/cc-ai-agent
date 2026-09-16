package com.ai.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Timeouts} 并发上限测试：超过 50 并发时快速失败(不再排队等待)。
 */
class TimeoutsConcurrencyTest {

    @Test
    void rejectsBeyondConcurrencyCap() throws Exception {
        // (tryAcquire 内部处理 InterruptedException)
        CountDownLatch holdAll = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        int cap = 50;
        // 占满 50 个并发名额
        for (int i = 0; i < cap; i++) {
            Thread t = new Thread(() -> {
                try {
                    Timeouts.call(() -> {
                        try {
                            holdAll.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        return "held";
                    }, 10_000);
                } catch (Exception ignored) {
                }
            });
            t.start();
            holders.add(t);
        }
        Thread.sleep(500); // 等待名额占满

        long start = System.currentTimeMillis();
        boolean rejected = false;
        try {
            Timeouts.call(() -> "never", 5_000);
        } catch (IllegalStateException e) {
            rejected = e.getMessage() != null && e.getMessage().contains("系统繁忙");
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(rejected, "超过并发上限应立即拒绝");
        assertTrue(elapsed < 1_000, "应快速失败而非等待超时, 实际 " + elapsed + "ms");

        holdAll.countDown();
        holders.forEach(Thread::interrupt);
    }
}
