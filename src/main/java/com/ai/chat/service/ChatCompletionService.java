package com.ai.chat.service;

import com.ai.chat.event.ChatCompletedEvent;
import com.ai.config.AppProperties;
import com.ai.context.ConversationMemory;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.RagMode;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话收尾(同步/流式共用)：记忆写回 → 摘要触发 → 完成审计事件 → 语义缓存写入 → 来源展示口径。
 *
 * <p>统一两条管线此前各自实现的"收尾"段。语义缓存的写入条件在此统一判定:
 * 正缓存仅写"未改写的独立问题 + KB 命中 + 回答未声明未找到";
 * 负缓存(穿透防护)仅写"检索已执行且零命中(非降级)"的无答案问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionService {

    private final ConversationMemory memoryService;
    private final SemanticAnswerCache semanticAnswerCache;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AppProperties appProperties;

    /**
     * 正常完成收尾：写回记忆/触发摘要/发布完成事件(真实来源)/写语义缓存,
     * 返回前端展示口径的来源列表(模型声明"未找到"时为空)。
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
        memoryService.append(session.getSessionId(), userMessage, answer);
        memoryService.summarizeIfNeededAsync(session.getSessionId());
        List<String> sourceNames = ChatSourceDisplay.sourceNames(prep.sources());
        publishCompleted(session, userMessage, answer, sourceNames,
                System.currentTimeMillis() - startMs, prep.rw(), prep.rag().mode(),
                prep.assembled() == null ? null : prep.assembled().composition(), usage);
        // 写入语义缓存: 正缓存(KB 命中且问题未改写且回答非"未找到") + 负缓存(穿透防护)
        if (prep.cacheEligible() && prep.rag().mode() == RagMode.KB) {
            if (!prep.rag().hits().isEmpty() && !ChatSourceDisplay.declaresNoResult(answer)) {
                semanticAnswerCache.put(prep.retrievalQuery(), answer, sourceNames);
            } else if (prep.rag().hits().isEmpty()
                    && prep.rag().outcome() != null
                    && prep.rag().outcome().executed()
                    && !prep.rag().outcome().degraded()) {
                // 检索真实执行且非降级: 零命中是确定性"无答案", 写短 TTL 负缓存防穿透
                // (超时/异常降级不入负缓存, 避免把瞬时故障误判为无答案)
                semanticAnswerCache.putMiss(prep.retrievalQuery());
            }
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
                sources, resolveModelLabel(), durationMs, rw, mode, composition, totalTokens));
    }

    /**
     * 解析对话日志用的模型名：优先取 ChatModel 默认选项中的实际模型,
     * 不可得时回退 app.chat.model-label 配置, 避免 chat_log.model_name 与实际模型漂移。
     *
     * @return 模型名标签
     */
    private String resolveModelLabel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null && chatModel.getDefaultOptions() != null
                && chatModel.getDefaultOptions().getModel() != null
                && !chatModel.getDefaultOptions().getModel().isBlank()) {
            return chatModel.getDefaultOptions().getModel();
        }
        return appProperties.getChat().getModelLabel();
    }
}
