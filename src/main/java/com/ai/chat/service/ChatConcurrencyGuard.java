package com.ai.chat.service;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 单用户并发对话信号量(B3)：同一用户最多同时进行 N 场对话(默认 3),
 * 超并发直接拒绝(429)——每场对话占用 1~2 次 LLM 调用与连接池资源, 无上限会被拖垮。
 *
 * <p>进程内实现(Caffeine 淘汰防泄漏); 多实例部署时升级 Redis 计数(见优化路线 P3)。
 * 淘汰窗口内极端场景可能短暂超限 1~2 个并发, 对保护目标影响可忽略。
 */
@Component
@RequiredArgsConstructor
public class ChatConcurrencyGuard {

    /** 释放句柄(实现 AutoCloseable, 配合 try-with-resources) */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private final Cache<String, Semaphore> semaphores = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    private final AppProperties appProperties;

    /**
     * 获取并发名额(限时等待, 超时抛 429)。
     *
     * @param userId 用户 ID
     * @return 释放句柄(用完必须 close)
     * @throws BusinessException CONCURRENT_LIMIT(6010, HTTP 429)
     */
    public Handle acquire(Long userId) {
        int max = appProperties.getConcurrency().getMaxConcurrentPerUser();
        long timeoutMs = appProperties.getConcurrency().getAcquireTimeoutMs();
        Semaphore semaphore = semaphores.get(String.valueOf(userId), k -> new Semaphore(max));
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.CONCURRENT_LIMIT);
        }
        return semaphore::release;
    }
}
