package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 切分策略配置启动校验(2026-10 切分路由的"引用漂移"防线):
 * {@code app.ingestion.chunking} 里引用的策略名必须在注入的策略集合中存在, 否则**拒绝启动**。
 *
 * <p>为什么必须: YAML 引用一个不存在的策略名, 运行时会静默落到兜底(递归切分对大多数格式都能
 * 出结果), 用户无感知——这正是"配置与实现分家"的静默失效。启动期炸出, 让配置错误立即可见。
 * 校验项: ① fallback-strategy 必配且存在; ② 每个 mime-to-strategy 的 value 存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkingConfigValidator implements ApplicationRunner {

    private final Map<String, ChunkingStrategy> strategies;
    private final AppProperties appProperties;

    @Override
    public void run(ApplicationArguments args) {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        if (!cfg.isEnabled()) {
            // 切分路由关闭: 无映射需要校验, 走 fallback 固定切分
            return;
        }
        if (cfg.getFallbackStrategy() == null || cfg.getFallbackStrategy().isBlank()) {
            throw new IllegalStateException(
                    "切分策略配置错误: app.ingestion.chunking.fallback-strategy 必配(切分路由开启时)");
        }
        requireStrategy(cfg.getFallbackStrategy(), "fallback-strategy");
        for (Map.Entry<String, String> e : cfg.getMimeToStrategy().entrySet()) {
            requireStrategy(e.getValue(), "mime-to-strategy[" + e.getKey() + "]");
        }
        log.info("切分策略配置校验通过: {} 条 MIME 映射, 兜底={}", cfg.getMimeToStrategy().size(),
                cfg.getFallbackStrategy());
    }

    private void requireStrategy(String name, String where) {
        if (strategies.get(name) == null) {
            throw new IllegalStateException("切分策略配置错误: " + where + " 引用的策略 '"
                    + name + "' 不存在(可用: " + String.join(", ", strategies.keySet()) + ")");
        }
    }
}
