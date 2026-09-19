package com.ai.rag.service;

import com.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按 {@code app.rag.rerank-mode} 取重排策略；未知取值回退 {@code score}（与历史行为一致）。
 *
 * <p>新增重排实现（如接入 gte-rerank 服务端）只要注册为 {@link RerankStrategy} Bean，
 * 这里会按 {@link RerankStrategy#mode()} 自动收录，无需改动编排层。
 */
@Slf4j
@Component
class RerankStrategyFactory {

    /** 兜底模式（纯计算、零额外调用） */
    private static final String DEFAULT_MODE = "score";

    private final Map<String, RerankStrategy> byMode = new HashMap<>();
    private final AppProperties appProperties;

    /**
     * 收录全部策略实现。
     *
     * @param strategies    Spring 注入的策略列表（按 mode 建索引）
     * @param appProperties 配置（提供当前 rerank-mode）
     */
    RerankStrategyFactory(List<RerankStrategy> strategies, AppProperties appProperties) {
        this.appProperties = appProperties;
        strategies.forEach(strategy -> byMode.put(strategy.mode(), strategy));
    }

    /**
     * 当前配置对应的策略。
     *
     * @return 重排策略；配置值无实现时为 score 融合
     */
    RerankStrategy current() {
        String configured = appProperties.getRag().getRerankMode();
        RerankStrategy strategy = byMode.get(normalize(configured));
        if (strategy != null) {
            return strategy;
        }
        log.warn("未知的 rerank-mode={} 已回退 {}", configured, DEFAULT_MODE);
        return requireDefault();
    }

    /** 规范化配置值（去空白与大小写） */
    private static String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    /** 取 score 策略；缺失说明装配出错（策略实现被误删），直接快速失败 */
    private RerankStrategy requireDefault() {
        RerankStrategy fallback = byMode.get(DEFAULT_MODE);
        if (fallback == null) {
            throw new IllegalStateException("缺少 " + DEFAULT_MODE + " 重排策略实现");
        }
        return fallback;
    }
}
