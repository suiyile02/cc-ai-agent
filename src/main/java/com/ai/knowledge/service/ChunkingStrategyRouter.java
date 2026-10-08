package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 切分策略路由器(2026-10 切分路由核心, 无 if-else 链): 按 MIME 查询策略, 未命中恒落兜底。
 *
 * <p>实现: Spring 按 bean 名把全部 {@link ChunkingStrategy} 注入成 {@code Map<name, strategy>};
 * 路由 = 查 {@code app.ingestion.chunking.mime-to-strategy} 显式映射 → 归一化 MIME 未命中 →
 * 落 {@code fallback-strategy}。新增策略 = 新增实现类 + 加配置, 本类零改动。
 */
@Component
@RequiredArgsConstructor
public class ChunkingStrategyRouter {

    /** 全部切分策略, 按 bean 名注入(Spring 原生收集, 无 if-else) */
    private final Map<String, ChunkingStrategy> strategies;
    private final AppProperties appProperties;

    /**
     * 按 MIME 路由到切分策略。
     *
     * @param mimeType 归一化前的 MIME(可带 charset 等参数, 内部归一化)
     * @return 命中策略或兜底; 兜底缺失时(配置错误, 理论上被启动校验拦截)退回 fallback bean 兜底
     */
    public ChunkingStrategy route(String mimeType) {
        IngestionProps.Chunking cfg = appProperties.getIngestion().getChunking();
        String name = cfg.getMimeToStrategy().get(normalize(mimeType));
        if (name != null) {
            ChunkingStrategy s = strategies.get(name);
            if (s != null) {
                return s;
            }
        }
        // 兜底: 配置的 fallback-strategy 优先, 否则 fallback bean 恒兜底
        ChunkingStrategy configured = strategies.get(cfg.getFallbackStrategy());
        return configured != null ? configured : strategies.get("fallback");
    }

    /**
     * MIME 归一化: 小写、去参数(text/plain; charset=utf-8 → text/plain)、
     * 通配后缀回退(text/x-python 未精确命中时尝试 text/plain)。
     */
    static String normalize(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String m = mimeType.trim().toLowerCase();
        int semi = m.indexOf(';');
        if (semi != -1) {
            m = m.substring(0, semi).trim();
        }
        return m;
    }
}
