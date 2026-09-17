package com.ai.chat.service;

import com.ai.chat.dto.SourceVO;
import com.ai.chat.event.ChatDecisionEvent;
import com.ai.common.Strings;
import com.ai.config.AppProperties;
import com.ai.context.AssembledPrompt;
import com.ai.context.service.ContextAssembler;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import com.ai.session.entity.ChatSession.SessionType;
import com.ai.system.entity.RagDecisionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 对话前置阶段(同步/流式共用)：改写 → 语义缓存查询 → 意图路由/混合检索 → 决策审计 → 装配。
 *
 * <p>统一两条管线此前各自实现的"准备"段——缓存的查询、决策落库、来源提取只存在一份代码。
 * 正缓存命中时跳过检索与装配, 但仍发布决策审计(KB/未执行);
 * 负缓存命中(穿透防护)同样短路, 直接返回固定"未找到"文案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPreparationService {

    private final QueryRewriter queryRewriter;
    private final SemanticAnswerCache semanticAnswerCache;
    private final IntentRouter intentRouter;
    private final RagRetriever ragRetriever;
    private final ContextAssembler contextAssembler;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 一次问答的 RAG 上下文快照(供提示词组装与决策落库)。
     *
     * @param hits    最终命中文档(已重排取 Top-K)
     * @param mode    意图路由结果 KB/GENERAL
     * @param outcome 检索结果明细(执行与否/各路命中数)
     */
    public record RagContext(List<Document> hits, RagMode mode,
                             RetrievalOutcome outcome) {

        /** 未检索的空上下文(AGENT 会话 / 常识问题跳过检索时使用) */
        public static RagContext empty() {
            return new RagContext(List.of(), RagMode.GENERAL, RetrievalOutcome.none());
        }
    }

    /** 前置阶段结果(语义缓存命中时 assembled 为 null, cachedAnswer 非空) */
    public record PreparedChat(QueryRewriter.RewriteResult rw, RagContext rag,
                               List<SourceVO> sources, AssembledPrompt assembled,
                               boolean cacheEligible, String retrievalQuery,
                               SemanticAnswerCache.CachedAnswer cachedAnswer) {
    }

    /**
     * 执行前置阶段：改写 → 语义缓存查询 → 意图路由/检索 + 决策审计 → 装配。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @return 前置阶段结果
     */
    public PreparedChat prepare(ChatSession session, String userMessage) {
        long start = System.currentTimeMillis();
        QueryRewriter.RewriteResult rw = queryRewriter.rewrite(
                session.getSessionId(), session.getSessionType(), userMessage);
        String retrievalQuery = rw.query() == null || rw.query().isBlank() ? userMessage : rw.query();
        long rewriteMs = System.currentTimeMillis() - start;

        boolean cacheEligible = cacheEligible(session, rw, retrievalQuery);
        long cacheStart = System.currentTimeMillis();
        SemanticAnswerCache.CachedAnswer cached =
                cacheEligible ? semanticAnswerCache.get(retrievalQuery) : null;
        boolean missHit = cacheEligible && cached == null && semanticAnswerCache.isMiss(retrievalQuery);
        long cacheMs = System.currentTimeMillis() - cacheStart;

        RagContext rag;
        List<SourceVO> sources;
        AssembledPrompt assembled;
        if (cached != null) {
            // 正缓存命中: 跳过检索与装配; 决策审计照常留痕(KB/未执行)
            rag = new RagContext(List.of(), RagMode.KB, RetrievalOutcome.none());
            sources = List.of();
            assembled = null;
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start);
            // 命中分支同样打印各段耗时: 此处是"缓存本该秒回却变慢"的唯一现场
            log.info("语义缓存命中: session={}, question={}, 改写 {}ms, 缓存查询 {}ms, 前置合计 {}ms",
                    session.getSessionId(), userMessage, rewriteMs, cacheMs,
                    System.currentTimeMillis() - start);
        } else if (missHit) {
            // 负缓存命中(穿透防护): 该问题此前检索零命中, 短时间直接返回固定"未找到"文案,
            // 跳过检索与模型调用; 审计口径与正缓存命中一致(KB/未执行, 不变式不被破坏)
            cached = new SemanticAnswerCache.CachedAnswer(ChatSourceDisplay.NO_RESULT_ANSWER, List.of());
            rag = new RagContext(List.of(), RagMode.KB, RetrievalOutcome.none());
            sources = List.of();
            assembled = null;
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start);
            log.info("语义负缓存命中(零命中问题短路): session={}, question={}, 改写 {}ms, 缓存查询 {}ms, 前置合计 {}ms",
                    session.getSessionId(), userMessage, rewriteMs, cacheMs,
                    System.currentTimeMillis() - start);
        } else {
            rag = resolveRagContext(session, retrievalQuery);
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start - rewriteMs);
            sources = ragRetriever.toSources(rag.hits());
            assembled = contextAssembler.assemble(
                    session, userMessage, rag.hits(), rag.mode(), rw.rewritten());
            log.info("对话前置阶段完成: session={}, 改写 {}ms, 缓存查询 {}ms, 路由+检索+装配 {}ms, 命中 {} 段",
                    session.getSessionId(), rewriteMs, cacheMs,
                    System.currentTimeMillis() - start - rewriteMs, rag.hits().size());
        }
        return new PreparedChat(rw, rag, sources, assembled, cacheEligible, retrievalQuery, cached);
    }

    /**
     * 判定本轮是否可尝试语义缓存: 已启用缓存、问题未经过多轮改写(改写问题依赖会话上下文,
     * 缓存会串味)、非 AGENT 会话、意图路由为 KB。
     *
     * @param session        会话
     * @param rw             查询改写结果
     * @param retrievalQuery 检索问题
     * @return true=可尝试缓存
     */
    private boolean cacheEligible(ChatSession session, QueryRewriter.RewriteResult rw,
            String retrievalQuery) {
        return appProperties.getSemanticCache().isEnabled()
                && !rw.rewritten()
                && session.getSessionType() != SessionType.AGENT
                && intentRouter.route(retrievalQuery) == RagMode.KB;
    }

    /**
     * 意图路由 + RAG 检索：AGENT 会话不检索；RAG/HYBRID 会话在 autoRoute=true 时按问题内容路由——
     * 工具类(mode=TOOL)/常识闲聊(mode=GENERAL)跳过检索, 知识库类问题(mode=KB)才执行混合检索。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @return RAG 上下文快照
     */
    private RagContext resolveRagContext(ChatSession session, String userMessage) {
        // 工具类问题(查订单/查物流等)答案在业务库, 知识库检索查不到——无条件跳过,
        // 不受 auto-route 开关影响; 模型在干净上下文下自主调 BusinessTools
        RagMode intent = intentRouter.route(userMessage);
        if (intent == RagMode.TOOL) {
            log.debug("工具类问题, 跳过检索(交给模型调工具): {}", userMessage);
            return new RagContext(List.of(), RagMode.TOOL, RetrievalOutcome.none());
        }
        boolean ragSession = session.getSessionType() == SessionType.RAG
                || session.getSessionType() == SessionType.HYBRID;
        if (!ragSession) {
            return RagContext.empty();
        }
        if (appProperties.getRag().isAutoRoute() && intent == RagMode.GENERAL) {
            log.debug("RAG 意图路由：通用/常识问题, 跳过检索 sessionId={}", session.getSessionId());
            return RagContext.empty();
        }
        log.debug("RAG 意图路由：知识库类问题, 执行检索 sessionId={}", session.getSessionId());
        RetrievalOutcome outcome = ragRetriever.retrieveOutcome(
                userMessage, appProperties.getRag().getTopK(),
                appProperties.getRag().getSimilarityThreshold());
        return new RagContext(outcome.hits(), RagMode.KB, outcome);
    }

    /**
     * 发布"意图路由/检索决策"事件(异步写 rag_decision_log, 模型失败也留痕)。
     *
     * <p>审计约定: {@code rag_mode=KB 且 retrieval_executed=false} 只可能是语义缓存命中
     * ——未命中时 KB 分支必定执行过检索; 此时不会产生 context_log(未装配上下文),
     * 据此可在日志中区分"缓存命中"与"检索了但没命中知识块"。
     *
     * @param session     会话
     * @param userMessage 用户消息(截断 500 保存)
     * @param rag         RAG 上下文快照(含路由模式与各路命中数)
     * @param costMs      决策与检索耗时
     */
    private void publishDecision(ChatSession session, String userMessage, RagContext rag, long costMs) {
        var outcome = rag.outcome() == null
                ? RetrievalOutcome.none() : rag.outcome();
        RagDecisionLog entry = new RagDecisionLog();
        entry.setSessionId(session.getSessionId());
        entry.setUserId(session.getUserId());
        entry.setUserMessage(Strings.truncate(userMessage, 500));
        entry.setRagMode(rag.mode().name());
        entry.setSessionType(session.getSessionType().name());
        entry.setRetrievalExecuted(rag.mode() == RagMode.KB && outcome.executed());
        entry.setSemanticHits(outcome.semanticCount());
        entry.setKeywordHits(outcome.keywordCount());
        entry.setFinalHits(rag.hits().size());
        entry.setTopK(appProperties.getRag().getTopK());
        entry.setSimilarityThreshold(appProperties.getRag().getSimilarityThreshold());
        entry.setRerankMode(appProperties.getRag().getRerankMode());
        entry.setDurationMs((int) costMs);
        eventPublisher.publishEvent(new ChatDecisionEvent(entry));
    }
}
