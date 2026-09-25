package com.ai.observability;

/**
 * 业务指标名集中定义(可观测性一期)：埋点处统一引用本类常量, 避免指标名散落拼写漂移。
 *
 * <p>采集后经 {@code /actuator/prometheus} 暴露, Grafana 关键面板:
 * 出口分布(chat.outcome) / 缓存命中率(semcache.*) / 检索延迟与降级(rag.retrieval*) /
 * Token 成本(chat.model.tokens) / 工具调用量与失败率(tool.calls) / 流式静默超时(chat.stream.idleTimeout)。
 */
public final class ChatMetrics {

    private ChatMetrics() {
    }

    /** 问答出口计数(tag: outcome=五出口)——回答质量画像 */
    public static final String OUTCOME = "chat.outcome";
    /** 模型 Token 消耗(按轮累加)——成本趋势 */
    public static final String MODEL_TOKENS = "chat.model.tokens";
    /** 语义缓存命中——命中率 = hit / (hit+miss) */
    public static final String SEMCACHE_HIT = "semcache.hit";
    /** 语义缓存未命中 */
    public static final String SEMCACHE_MISS = "semcache.miss";
    /** 语义负缓存写入(无据可依拒答的重复问题拦截数) */
    public static final String SEMCACHE_NEGATIVE = "semcache.negative";
    /** 检索耗时 Timer */
    public static final String RETRIEVAL = "rag.retrieval";
    /** 检索超时/失败降级次数(告警核心指标) */
    public static final String RETRIEVAL_DEGRADED = "rag.retrieval.degraded";
    /** 工具调用计数(tag: tool/status) */
    public static final String TOOL_CALLS = "tool.calls";
    /** 流式静默超时终止次数 */
    public static final String STREAM_IDLE_TIMEOUT = "chat.stream.idleTimeout";
}
