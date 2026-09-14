package com.ai.context.service;

import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.context.ConversationMemory;
import com.ai.session.entity.ChatSession.SessionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 多轮查询改写器：基于 Spring AI {@link CompressionQueryTransformer} 把"对话历史 + 当前追问"
 * 压缩为语义完整、可独立检索的问题(指代消解 + 省略补全)。
 *
 * <p>触发条件：非 AGENT 会话、已有历史轮次、模型可用；改写调用同步执行且受超时与熔断保护——
 * 连续失败达到阈值后进入冷却期, 冷却期内直接回退原始问题(零等待), 冷却结束放行探测, 成功即复位。
 * 模型不可用/超时/失败/结果为空一律回退原始问题, 不阻断主流程。
 * 改写结果仅用于意图路由与检索, 原始问题仍用于对话与日志。
 */
@Slf4j
@Service
public class QueryRewriter {

    /** 改写时参考的最近历史消息条数上限 */
    private static final int HISTORY_LIMIT = 6;
    /** 熔断阈值: 连续失败达到该次数后进入冷却 */
    private static final int BREAKER_FAIL_THRESHOLD = 2;
    /** 熔断冷却时长(ms): 冷却期内跳过改写, 避免每次请求都白等满超时 */
    private static final long BREAKER_COOLDOWN_MS = 60_000;

    private final ConversationMemory memoryService;
    private final AppProperties appProperties;
    private final ChatClientProvider chatClientProvider;
    /** 压缩查询转换器: 生产路径按 ChatClient 可用性惰性构建; 测试可直接注入 mock */
    private final CompressionQueryTransformer transformer;

    /** 熔断状态: 连续失败次数与冷却截止时间 */
    private int consecutiveFailures = 0;
    private volatile long breakerOpenUntil = 0;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryRewriter(ConversationMemory memoryService, AppProperties appProperties,
            ChatClientProvider chatClientProvider) {
        this(memoryService, appProperties, chatClientProvider, null);
    }

    QueryRewriter(ConversationMemory memoryService, AppProperties appProperties,
            ChatClientProvider chatClientProvider, CompressionQueryTransformer transformer) {
        this.memoryService = memoryService;
        this.appProperties = appProperties;
        this.chatClientProvider = chatClientProvider;
        this.transformer = transformer;
    }

    /**
     * 改写结果。
     *
     * @param query     用于检索/路由的问题(改写后或原始)
     * @param rewritten 是否实际发生了改写
     */
    public record RewriteResult(String query, boolean rewritten) {
    }

    /**
     * 结合历史改写当前问题。
     *
     * @param sessionId   会话 ID
     * @param sessionType 会话类型(AGENT 会话跳过改写)
     * @param userMessage 当前用户问题
     * @return 改写结果; 不满足触发条件或失败时返回原始问题(rewritten=false)
     */
    public RewriteResult rewrite(String sessionId, SessionType sessionType, String userMessage) {
        AppProperties.Context.QueryRewrite cfg = appProperties.getContext().getQueryRewrite();
        if (!cfg.isEnabled() || sessionType == SessionType.AGENT
                || userMessage == null || userMessage.isBlank()) {
            return new RewriteResult(userMessage, false);
        }
        if (memoryService.messageCount(sessionId) < cfg.getMinHistoryTurns()) {
            return new RewriteResult(userMessage, false); // 首轮无需改写
        }
        if (cfg.isReferenceHintRequired() && !containsReferenceHint(userMessage)) {
            // 完整问题(无指代/省略线索)零等待直接检索, 避免每轮白等一次改写调用
            log.debug("查询改写跳过: 问题不含指代线索, 视为独立问题: {}", userMessage);
            return new RewriteResult(userMessage, false);
        }
        CompressionQueryTransformer transformer = resolveTransformer();
        if (transformer == null) {
            return new RewriteResult(userMessage, false); // 模型不可用
        }
        if (System.currentTimeMillis() < breakerOpenUntil) {
            log.debug("查询改写熔断冷却中, 跳过改写: {}", userMessage);
            return new RewriteResult(userMessage, false); // 冷却期内零等待降级
        }

        long start = System.currentTimeMillis();
        try {
            // 获取历史消息
            List<Message> history = memoryService.recentMessages(sessionId, HISTORY_LIMIT);
            Query standalone = Timeouts.call(() -> transformer.transform(
                    Query.builder().text(userMessage).history(history).build()), cfg.getTimeoutMs());
            long elapsed = System.currentTimeMillis() - start;
            consecutiveFailures = 0;
            String rewritten = standalone == null ? null : standalone.text();
            if (rewritten == null || rewritten.isBlank() || rewritten.trim().equals(userMessage.trim())) {
                log.info("查询改写完成但结果等同原问题: {}ms", elapsed);
                return new RewriteResult(userMessage, false);
            }
            log.info("查询改写成功: {}ms, {} => {}", elapsed, userMessage, rewritten);
            return new RewriteResult(rewritten.trim(), true);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            consecutiveFailures++;
            if (consecutiveFailures >= BREAKER_FAIL_THRESHOLD) {
                breakerOpenUntil = System.currentTimeMillis() + BREAKER_COOLDOWN_MS;
                consecutiveFailures = 0;
                log.warn("查询改写连续失败进入熔断({}ms 冷却), 期间直接回退原始问题: 耗时 {}ms, 原因 {}",
                        BREAKER_COOLDOWN_MS, elapsed, e.getMessage());
            } else {
                log.warn("查询改写失败/超时({}ms), 回退原始问题: {}", elapsed, e.getMessage());
            }
            return new RewriteResult(userMessage, false);
        }
    }

    /**
     * 获取压缩查询转换器: 注入的实例优先; 否则按 ChatClient 可用性惰性构建并缓存。
     *
     * @return 转换器, 模型不可用时 null
     */
    private CompressionQueryTransformer resolveTransformer() {
        if (transformer != null) {
            return transformer;
        }
        var builder = chatClientProvider.getBuilderIfAvailable();
        if (builder == null) {
            return null;
        }
        // 改写是机械性压缩任务, 注入 enable_thinking=false 关闭 qwen3 思维链(实测大幅降延迟)
        if (appProperties.getContext().getQueryRewrite().isDisableThinking()) {
            builder.defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false)));
        }
        return CompressionQueryTransformer.builder().chatClientBuilder(builder).build();
    }

    /**
     * 判断消息是否包含指代/省略线索词(如"那/它/这个")。
     *
     * @param message 用户消息
     * @return true=含线索词, 需要改写
     */
    private boolean containsReferenceHint(String message) {
        List<String> hints = appProperties.getContext().getQueryRewrite().getReferenceHints();
        if (hints == null) {
            return true; // 词表被清空则退回"始终改写", 保证不误跳过
        }
        return hints.stream().anyMatch(message::contains);
    }
}
