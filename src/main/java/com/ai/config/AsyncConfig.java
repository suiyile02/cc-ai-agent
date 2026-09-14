package com.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档入库异步线程池(与需求 2.2 一致)：入库不阻塞上传请求线程。
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    /**
     * 创建文档入库专用线程池。
     *
     * @return Executor(核心 5 / 最大 20 / 队列 100, 线程名前缀 doc-ingestion-)
     */
    @Bean(name = "ingestionExecutor")
    public Executor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-ingestion-");
        // 队列满时由提交线程自己执行(降级为同步入库), 不静默丢弃任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
