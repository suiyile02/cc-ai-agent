package com.ai.context.service;
import com.ai.context.AssembledPrompt;
import com.ai.context.ContextComposition;
import com.ai.context.ConversationMemory;
import com.ai.context.HistoryContext;

import com.ai.prompt.PromptService;
import com.ai.common.TokenCounter;
import com.ai.config.AppProperties;
import com.ai.session.entity.ChatSession;
import com.ai.rag.ChatOutcome;
import com.ai.rag.RagRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一上下文装配层：在总 Token 预算内按占比切分 system/history/rag/user 四段,
 * 依次完成 RAG 上下文注入(受预算约束)、system 组装、历史读取(摘要 + 窗口 + 预算)、
 * 用户输入截断, 产出可直接喂给 {@code ChatClient} 的 {@link AssembledPrompt}。
 *
 * <p>意图路由与检索仍在 {@code ChatService} 完成(基于改写后的问题), 本层只负责预算与装配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    /** 摘要注入为 SYSTEM 消息时的前缀 */
    private static final String SUMMARY_PREFIX = "【早前对话摘要】\n";

    private final RagRetriever ragRetriever;
    private final PromptService promptService;
    private final ConversationMemory memoryService;
    private final TokenCounter tokenCounter;
    private final AppProperties appProperties;

    /**
     * 装配一次问答的完整上下文。
     *
     * @param session     会话
     * @param userMessage 当前用户问题(原始, 用于模型 user 轮)
     * @param ragHits     检索命中文档(已重排取 Top-K; 无检索时为空)
     * @param outcome     本轮出口(决定 system 模板; P3-7 起取代意图路由预判)
     * @param rewritten   当前问题是否经过多轮改写(仅用于可观测标记)
     * @return 装配结果(system + 历史消息 + 用户输入 + Token 组成)
     */
    public AssembledPrompt assemble(ChatSession session, String userMessage,
            List<Document> ragHits, ChatOutcome outcome, boolean rewritten) {
        AppProperties.Context cfg = appProperties.getContext();
        // 获取会话的 Token 默认预算配置
        int total = Math.max(0, cfg.getModelMaxTokens() - cfg.getReservedForAnswer());
        AppProperties.Context.Budget budget = cfg.getBudget();
        int ragBudget = (int) (total * budget.getRag());
        int historyBudget = (int) (total * budget.getHistory());
        int userBudget = (int) (total * budget.getUser());

        // RAG 段: 按预算构建注入文本
        boolean hasHits = ragHits != null && !ragHits.isEmpty();
        String contextText = ragRetriever.buildContext(ragHits, ragBudget);
        int ragTokens = tokenCounter.count(contextText);

        // system 段: base + 可选 rag-context 模板
        String system = promptService.systemFor(session.getSessionType(), outcome, hasHits, contextText);
        int systemTokens = Math.max(0, tokenCounter.count(system) - ragTokens);

        // history 段: 摘要 + 最近窗口(受预算约束, 内部触发滚动摘要)
        HistoryContext history =
                memoryService.loadHistory(session.getSessionId(), historyBudget);

        // user 段: 超预算保留尾部
        String queryForModel = userMessage == null ? "" : userMessage;
        boolean userTruncated = false;
        if (tokenCounter.count(queryForModel) > userBudget) {
            queryForModel = tailTruncate(queryForModel, userBudget);
            userTruncated = true;
            log.warn("用户输入超出预算被截断: session={}", session.getSessionId());
        }
        int userTokens = tokenCounter.count(queryForModel);

        // 组装消息: [摘要 SYSTEM?] + 历史(时间升序)
        List<Message> messages = new ArrayList<>();
        if (history.summary() != null && !history.summary().isBlank()) {
            messages.add(new SystemMessage(SUMMARY_PREFIX + history.summary()));
        }
        messages.addAll(history.messages());

        boolean truncated = history.truncated() || userTruncated;
        int totalTokens = systemTokens + ragTokens + history.historyTokens() + userTokens;
        ContextComposition composition = new ContextComposition(
                systemTokens, history.historyTokens(), history.summaryTokens(),
                ragTokens, userTokens, totalTokens, cfg.getModelMaxTokens(),
                history.messages().size(), truncated, rewritten);
        log.debug("上下文装配: session={}, total={}tok (sys={}, rag={}, his={}, user={}), truncated={}",
                session.getSessionId(), totalTokens, systemTokens, ragTokens,
                history.historyTokens(), userTokens, truncated);
        return new AssembledPrompt(system, messages, queryForModel, composition);
    }

    /**
     * 保留尾部截断(问题通常位于输入末尾)。
     *
     * @param text      原始文本
     * @param maxTokens token 上限
     * @return 截断后的尾部文本
     */
    private String tailTruncate(String text, int maxTokens) {
        if (maxTokens <= 0) {
            return "";
        }
        int total = tokenCounter.count(text);
        if (total <= maxTokens) {
            return text;
        }
        int keep = Math.max(1, (int) ((long) text.length() * maxTokens / total));
        String cut = text.substring(text.length() - keep);
        while (cut.length() > 1 && tokenCounter.count(cut) > maxTokens) {
            cut = cut.substring(Math.max(1, (int) (cut.length() * 0.1)));
        }
        return cut;
    }
}
