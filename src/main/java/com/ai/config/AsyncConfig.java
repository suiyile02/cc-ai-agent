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
@org.springframework.scheduling.annotation.EnableScheduling
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

    /**
     * 审计异步落库专用线程池：对话审计事件(chat_log/context_log/rag_decision_log)的异步写库
     * 与文档入库解耦, 互不挤占; 同时让 {@code @Async} 显式指定名字, 消除 Spring 在
     * "ingestionExecutor 与 taskScheduler 间无名为 taskExecutor 的 bean" 的歧义告警。
     *
     * @return Executor(核心 2 / 最大 8 / 队列 500, 线程名前缀 audit-)
     */
    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 会话标题精修线程池：一次模型调用, 只为让标题好看一点, 不值得与审计落库抢线程。
     *
     * <p><b>刻意使用默认的 AbortPolicy(队列满即拒绝)</b>：{@code auditExecutor}/{@code ingestionExecutor}
     * 用 CallerRunsPolicy 是因为那两类任务不可丢(丢了就少一条审计/文档卡在处理中); 而标题精修
     * 被丢弃时兜底标题已经落库, 损失仅是"标题没那么好看"。若沿用 CallerRunsPolicy, 队列一满
     * 就会把一次几秒的模型调用放回<b>对话请求线程</b>上执行, 等于给对话凭空加上那段延迟。
     * 拒绝由 {@code SessionTitleService#refineAsync} 捕获并 WARN。
     *
     * @return Executor(核心 1 / 最大 2 / 队列 50, 线程名前缀 session-title-)
     */
    @Bean(name = "sessionTitleExecutor")
    public Executor sessionTitleExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("session-title-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
