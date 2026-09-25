package com.ai.chat.service;

import com.ai.chat.event.ChatCompletedEvent;
import com.ai.config.ChatClientProvider;
import com.ai.context.ConversationMemory;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.ChatOutcome;
import com.ai.rag.RagMode;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话收尾(同步/流式共用)：记忆写回 → 摘要触发 → 完成审计事件 → 语义缓存写入 → 来源展示口径。
 *
 * <p>统一两条管线此前各自实现的"收尾"段。语义缓存的写入条件在此统一判定:
 * 正缓存仅写"未改写的独立问题 + KB 命中 + 回答未声明未找到 + 本轮未调用工具";
 * 负缓存(穿透防护)仅写"检索已执行且零命中(非降级, 且本轮未调用工具)"的无答案问题。
 * 缓存条目跨用户共享, 故工具轮次(答案含业务库实时数据)一律排除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    private final ConversationMemory memoryService;
    private final SemanticAnswerCache semanticAnswerCache;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatClientProvider chatClientProvider;

    /**
     * 正常完成收尾：写回记忆/触发摘要/发布完成事件(真实来源)/写语义缓存,
     * 返回前端展示口径的来源列表(模型声明"未找到"时为空)。
     *
     * <p>缓存写入前置条件之一: {@code prep.toolCalls()} 为 0——本轮调用过工具时答案含
     * 业务库实时数据(员工联系方式/订单状态), 而缓存是跨用户共享的, 写入即造成串数据。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果
     * @param answer      回答全文
     * @param usage       模型 Token 用量(可空)
     * @param startMs     请求开始时间戳
     */
    public void complete(ChatSession session, String userMessage,
            ChatPreparationService.PreparedChat prep, String answer, Integer usage, long startMs) {
        // 写回记忆/触发摘要/发布完成事件/写入语义缓存
        memoryService.append(session.getSessionId(), userMessage, answer);
        memoryService.summarizeIfNeededAsync(session.getSessionId());
        List<String> sourceNames = ChatSourceDisplay.sourceNames(prep.sources());
        publishCompleted(session, userMessage, answer, sourceNames,
                System.currentTimeMillis() - startMs, prep.rw(), prep.rag().mode(),
                prep.assembled() == null ? null : prep.assembled().composition(), usage);
        // 可观测性埋点: 出口分布(回答质量画像) + Token 成本
        ChatOutcome outcome = prep.rag().chatOutcome();
        io.micrometer.core.instrument.Metrics.counter(com.ai.observability.ChatMetrics.OUTCOME,
                "outcome", outcome.name()).increment();
        if (usage != null && usage > 0) {
            io.micrometer.core.instrument.Metrics
                    .counter(com.ai.observability.ChatMetrics.MODEL_TOKENS).increment(usage);
        }
        // 写入语义缓存: 正缓存 只有"独立原始问题(未经过改写/扩展) + 真查到知识库依据 + 没走工具"的回答才进缓存
        // 负缓存(本轮判定为"无据可依"的拒答, 用于重复无据问题省一次模型调用)。
        // 本轮调用过工具 → 回答含业务库实时数据(员工联系方式/订单状态), 且缓存条目跨用户共享,
        // 因此正/负缓存一律不写(否则他人同问即命中这条带他人数据的答案)。
        int toolCalls = prep.toolCalls().get();
        if (toolCalls > 0) {
            log.info("本轮发生 {} 次工具调用, 跳过语义缓存写入: session={}, question={}",
                    toolCalls, session.getSessionId(), prep.retrievalQuery());
        } else if (prep.cacheEligible() && outcome == ChatOutcome.ANSWERED_FROM_KB
                && !prep.rag().hits().isEmpty() && !ChatSourceDisplay.declaresNoResult(answer)) {
            semanticAnswerCache.put(prep.retrievalQuery(), answer, sourceNames);
        } else if (prep.cacheEligible() && outcome == ChatOutcome.REFUSED_NO_EVIDENCE) {
            // 只给"无据可依"写负缓存: 旧实现按"零命中"写, 会把宽松模式下本可自由作答的问题
            // 也缓存成固定"未找到"文案——出口化之后这类轮次是 ANSWERED_OPEN, 不再误入负缓存
            semanticAnswerCache.putMiss(prep.retrievalQuery());
        }
    }

    /**
     * 语义缓存命中收尾：仅写回记忆/摘要/审计(不写缓存——命中不产生新信息)。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果(cachedAnswer 非空)
     * @param startMs     请求开始时间戳
     */
    public void completeCached(ChatSession session, String userMessage,
            ChatPreparationService.PreparedChat prep, long startMs) {
        io.micrometer.core.instrument.Metrics.counter(com.ai.observability.ChatMetrics.OUTCOME,
                "outcome", com.ai.rag.ChatOutcome.ANSWERED_FROM_CACHE.name()).increment();
        memoryService.append(session.getSessionId(), userMessage, prep.cachedAnswer().content());
        memoryService.summarizeIfNeededAsync(session.getSessionId());
        publishCompleted(session, userMessage, prep.cachedAnswer().content(),
                prep.cachedAnswer().sources(), System.currentTimeMillis() - startMs,
                prep.rw(), RagMode.KB, null, null);
    }

    /**
     * 中断收尾(流式生成失败)：以友好提示收尾并审计, 不写语义缓存。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param answer      兜底提示文本
     * @param startMs     请求开始时间戳
     */
    public void completeInterrupted(ChatSession session, String userMessage, String answer, long startMs) {
        memoryService.append(session.getSessionId(), userMessage, answer);
        memoryService.summarizeIfNeededAsync(session.getSessionId());
        publishCompleted(session, userMessage, answer, List.of(),
                System.currentTimeMillis() - startMs,
                new QueryRewriter.RewriteResult(userMessage, false), RagMode.GENERAL, null, null);
    }

    /**
     * 发布"问答完成"事件(异步写 chat_log 与 context_log)。
     *
     * @param session     会话
     * @param userMessage 原始用户消息
     * @param answer      回答文本
     * @param sources     引用来源(真实召回, 审计不过滤)
     * @param durationMs  总耗时
     * @param rw          查询改写结果
     * @param mode        意图路由结果
     * @param composition 上下文组成快照(null=不写 context_log)
     * @param totalTokens 模型 Token 用量(可空)
     */
    private void publishCompleted(ChatSession session, String userMessage, String answer,
            List<String> sources, long durationMs, QueryRewriter.RewriteResult rw,
            RagMode mode, com.ai.context.ContextComposition composition, Integer totalTokens) {
        eventPublisher.publishEvent(new ChatCompletedEvent(session, userMessage, answer,
                sources, chatClientProvider.modelLabel(), durationMs, rw, mode, composition, totalTokens));
    }
}
