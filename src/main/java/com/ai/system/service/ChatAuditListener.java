package com.ai.system.service;

import com.ai.chat.event.ChatCompletedEvent;
import com.ai.chat.event.ChatDecisionEvent;
import com.ai.context.ContextComposition;
import com.ai.context.service.QueryRewriter;
import com.ai.system.entity.ContextLog;
import com.ai.system.entity.RagDecisionLog;
import com.ai.common.Strings;
import com.ai.rag.RagMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 对话审计监听器：异步消费对话模块发布的事件, 写入 rag_decision_log / chat_log / context_log。
 *
 * <p>把审计落库从对话关键路径剥离——监听失败仅记录告警, 绝不影响问答主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAuditListener {

    /** context_log.user_message / rewritten_query 的保存上限 */
    private static final int LOG_TEXT_MAX_CHARS = 500;

    private final RagDecisionLogService ragDecisionLogService;
    private final ChatLogService chatLogService;
    private final ContextLogService contextLogService;
    private final ObjectMapper objectMapper;

    /**
     * 写入意图路由/检索决策日志(模型调用前发布, 模型失败也留痕)。
     *
     * @param event 决策事件
     */
    @Async("auditExecutor")
    @EventListener
    public void onDecision(ChatDecisionEvent event) {
        try {
            ragDecisionLogService.save(toEntity(event));
        } catch (Exception e) {
            log.warn("写入 RAG 决策日志失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 决策快照 → 持久化实体(存储结构只在本模块出现)。
     *
     * @param event 决策事件
     * @return 待落库的决策日志记录
     */
    private RagDecisionLog toEntity(ChatDecisionEvent event) {
        RagDecisionLog entry = new RagDecisionLog();
        entry.setSessionId(event.sessionId());
        entry.setUserId(event.userId());
        entry.setUserMessage(event.userMessage());
        entry.setRagMode(event.ragMode());
        entry.setSessionType(event.sessionType());
        entry.setRetrievalExecuted(event.retrievalExecuted());
        entry.setSemanticHits(event.semanticHits());
        entry.setKeywordHits(event.keywordHits());
        entry.setFinalHits(event.finalHits());
        entry.setTopK(event.topK());
        entry.setSimilarityThreshold(event.similarityThreshold());
        entry.setRerankMode(event.rerankMode());
        entry.setDurationMs((int) event.durationMs());
        return entry;
    }

    /**
     * 写入对话日志(来源序列化为 JSON)与上下文装配日志(仅管线模式)。
     *
     * @param event 问答完成事件
     */
    @Async("auditExecutor")
    @EventListener
    public void onChatCompleted(ChatCompletedEvent event) {
        recordChatLog(event);
        recordContextLog(event);
    }

    /**
     * 组装并保存对话日志记录(chat_log)。
     *
     * @param event 问答完成事件
     */
    private void recordChatLog(ChatCompletedEvent event) {
        try {
            String sourcesJson = objectMapper.writeValueAsString(event.sources());
            chatLogService.record(event.session(), event.userMessage(), event.answer(),
                    sourcesJson, null, event.modelLabel(), (int) event.durationMs(),
                    event.totalTokens());
        } catch (Exception e) {
            log.warn("写入对话日志失败(忽略): {}", e.getMessage());
        }
    }

    /**
     * 组装并保存上下文装配日志(context_log); 无组成快照时跳过。
     *
     * @param event 问答完成事件
     */
    private void recordContextLog(ChatCompletedEvent event) {
        ContextComposition c = event.composition();
        if (c == null) {
            return;
        }
        try {
            QueryRewriter.RewriteResult rw = event.rewrite();
            ContextLog entry = new ContextLog();
            entry.setSessionId(event.session().getSessionId());
            entry.setUserId(event.session().getUserId());
            entry.setUserMessage(Strings.truncate(event.userMessage(), LOG_TEXT_MAX_CHARS));
            entry.setRewrittenQuery(rw != null && rw.rewritten() ? Strings.truncate(rw.query(), LOG_TEXT_MAX_CHARS) : null);
            entry.setIntentMode(event.intentMode() == null ? null : event.intentMode().name());
            entry.setSystemTokens(c.systemTokens());
            entry.setHistoryTokens(c.historyTokens());
            entry.setSummaryTokens(c.summaryTokens());
            entry.setRagTokens(c.ragTokens());
            entry.setUserTokens(c.userTokens());
            entry.setTotalTokens(c.totalTokens());
            entry.setModelMaxTokens(c.modelMaxTokens());
            entry.setHistoryMessages(c.historyMessages());
            entry.setTruncated(c.truncated());
            entry.setDurationMs((int) event.durationMs());
            contextLogService.save(entry);
        } catch (Exception e) {
            log.warn("写入上下文装配日志失败(忽略): {}", e.getMessage());
        }
    }

}
