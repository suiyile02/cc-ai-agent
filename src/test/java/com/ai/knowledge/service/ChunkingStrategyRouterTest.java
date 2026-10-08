package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 切分策略路由单元测试(2026-10 切分路由):
 * ① 配置映射命中 → 对应策略; ② 未配置 MIME → 兜底策略; ③ MIME 归一化(大小写/参数);
 * ④ 启动校验——fallback 缺失/引用不存在的策略名 → 拒绝启动(引用漂移防线)。
 */
class ChunkingStrategyRouterTest {

    private final FallbackChunker fallback = new FallbackChunker(new AppProperties());
    private final ChineseRecursiveChunker recursive = new ChineseRecursiveChunker(new AppProperties());
    private final Map<String, ChunkingStrategy> strategies =
            Map.of("fallback", fallback, "recursive-cn", recursive);

    private AppProperties props(String fallbackName, Map<String, String> mapping) {
        AppProperties p = new AppProperties();
        IngestionProps.Chunking cfg = p.getIngestion().getChunking();
        cfg.setFallbackStrategy(fallbackName);
        cfg.setMimeToStrategy(mapping);
        return p;
    }

    @Test
    void configuredMimeRoutesToMappedStrategy() {
        AppProperties p = props("fallback", Map.of("text/markdown", "recursive-cn"));
        ChunkingStrategyRouter router = new ChunkingStrategyRouter(strategies, p);

        assertSame(recursive, router.route("text/markdown"));
    }

    @Test
    void unconfiguredMimeFallsBackToFallbackStrategy() {
        AppProperties p = props("fallback", Map.of("text/markdown", "recursive-cn"));
        ChunkingStrategyRouter router = new ChunkingStrategyRouter(strategies, p);

        assertSame(fallback, router.route("application/x-unknown-42"),
                "未配置的 MIME 必须落兜底策略, 路由永不 miss");
        assertSame(fallback, router.route(null), "MIME 缺失同样落兜底");
    }

    @Test
    void mimeNormalizationStripsParamsAndCase() {
        AppProperties p = props("fallback", Map.of("text/markdown", "recursive-cn"));
        ChunkingStrategyRouter router = new ChunkingStrategyRouter(strategies, p);

        assertSame(recursive, router.route("text/markdown; charset=utf-8"),
                "带 charset 参数必须归一化后命中");
        assertSame(recursive, router.route("  TEXT/MARKDOWN  "),
                "大小写与空白必须归一化后命中");
    }

    /* ---------------- 启动校验(引用漂移防线) ---------------- */

    @Test
    void validatorRejectsMissingFallbackStrategy() {
        AppProperties p = props(null, Map.of());
        ChunkingConfigValidator validator = new ChunkingConfigValidator(strategies, p);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator.run(null));
        assertEquals(true, e.getMessage().contains("fallback-strategy"));
    }

    @Test
    void validatorRejectsUnknownStrategyReference() {
        AppProperties p = props("fallback", Map.of("text/markdown", "no-such-strategy"));
        ChunkingConfigValidator validator = new ChunkingConfigValidator(strategies, p);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator.run(null));
        assertEquals(true, e.getMessage().contains("no-such-strategy"),
                "引用不存在的策略名必须拒绝启动(否则运行时静默落兜底)");
        assertEquals(true, e.getMessage().contains("recursive-cn"),
                "错误信息应列出可用策略名, 便于运维修正");
    }

    @Test
    void validatorPassesWithValidConfig() {
        AppProperties p = props("fallback", Map.of(
                "text/markdown", "recursive-cn",
                "text/plain", "recursive-cn"));
        ChunkingConfigValidator validator = new ChunkingConfigValidator(strategies, p);

        validator.run(null); // 不抛即通过
    }

    @Test
    void validatorSkippedWhenChunkingDisabled() {
        AppProperties p = props(null, Map.of());  // 故意不配 fallback
        p.getIngestion().getChunking().setEnabled(false);
        ChunkingConfigValidator validator = new ChunkingConfigValidator(strategies, p);

        validator.run(null); // 总开关关闭时不做校验(走 fallback 固定切分)
    }
}
