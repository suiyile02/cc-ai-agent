package com.ai.chat.service;

import com.ai.rag.SourceVO;
import com.ai.chat.event.ChatDecisionEvent;
import com.ai.common.Strings;
import com.ai.config.AppProperties;
import com.ai.context.AssembledPrompt;
import com.ai.context.service.ContextAssembler;
import com.ai.context.service.QueryRewriter;
import com.ai.context.service.ShortQueryExpander;
import com.ai.rag.ChatOutcome;
import com.ai.rag.IntentRouter;
import com.ai.rag.OutcomeResolver;
import com.ai.rag.RagMode;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import com.ai.session.SessionType;
import com.ai.session.service.SessionTitleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话前置阶段(同步/流式共用)：改写 → 短查询扩展 → 检索 → **出口判定** → 语义缓存查询 → 决策审计 → 装配。
 *
 * <p>统一两条管线此前各自实现的"准备"段——缓存的查询、决策落库、来源提取只存在一份代码。
 * 检索调试接口({@link #debugSearch})也走这同一条链的"扩展+检索+出口判定"三步，
 * 避免"调试页能查到、对话却拒答"这类口径分叉。
 *
 * <p><b>检索先于缓存查询</b>(P3-7)：出口只能由检索事实算出，词表不再预判"该不该检索"，
 * 因此正缓存命中时本轮确实付了一次检索(代价实测 159~321ms)；换来的收益是
 * "知识库里的内容能否被问到"不再取决于有人记得改配置。缓存命中不再由"KB+未执行检索"推断，
 * 而是记为一等出口 {@code ANSWERED_FROM_CACHE}。
 *
 * <p>负缓存语义随之改变：它现在只省一次注定拒答的<b>模型调用</b>，不再跳过检索，
 * 且只在出口为 {@code REFUSED_NO_EVIDENCE} 时读写——绝不让一条旧的"没查到"覆盖刚查到的证据。
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
    private final SessionTitleService sessionTitleService;
    private final ShortQueryExpander shortQueryExpander;

    /**
     * 一次问答的 RAG 上下文快照(供提示词组装与决策落库)。
     *
     * @param hits        最终注入上下文的文档(已重排取 Top-K)
     * @param mode        意图路由预判 KB/GENERAL/TOOL —— 仅填旧审计列与诊断参考,
     *                    <b>不再参与作答口径判定</b>(判定看 {@code chatOutcome})
     * @param outcome     检索结果明细(执行与否/各路命中数/阈值过滤前最大分)
     * @param chatOutcome 本轮作答依据的出口(由检索事实事后算出)
     */
    public record RagContext(List<Document> hits, RagMode mode,
                             RetrievalOutcome outcome, ChatOutcome chatOutcome) {

        /** 未执行检索的空上下文(AGENT 会话 / 非 RAG 会话) */
        public static RagContext empty() {
            return new RagContext(List.of(), RagMode.GENERAL, RetrievalOutcome.none(),
                    ChatOutcome.ANSWERED_OPEN);
        }
    }

    /**
     * 前置阶段结果(语义缓存命中时 assembled 为 null, cachedAnswer 非空)。
     *
     * @param toolCalls 本轮工具调用计数容器(经 toolContext 透传给 ToolCallLogAspect 自增);
     *                  收尾阶段据此判断"回答是否含实时业务数据", 非零则禁止写语义缓存
     */
    public record PreparedChat(QueryRewriter.RewriteResult rw, RagContext rag,
                               List<SourceVO> sources, AssembledPrompt assembled,
                               boolean cacheEligible, String retrievalQuery,
                               SemanticAnswerCache.CachedAnswer cachedAnswer,
                               AtomicInteger toolCalls) {
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
        // 本轮工具调用计数(与"一次问答"同生命周期, 由 ChatService 注入 toolContext)
        AtomicInteger toolCalls = new AtomicInteger();
        // 首轮标题: 先同步占位, 再把精修丢进线程池——它只需问题, 因此与下面的检索与模型回答并行
        String fallbackTitle = sessionTitleService.claimFallback(session, userMessage);
        if (fallbackTitle != null) {
            sessionTitleService.refineAsync(session.getSessionId(), userMessage, fallbackTitle);
        }
        // 查询改写
        QueryRewriter.RewriteResult rw = queryRewriter.rewrite(
                session.getSessionId(), session.getSessionType(), userMessage);
        String retrievalQuery = rw.query() == null || rw.query().isBlank() ? userMessage : rw.query();
        // 短查询扩展: 裸词("产品")的余弦分偏低会被出口判据误杀, 补全成完整问题再检索。
        // 扩展成功即视为"已改写"——语义缓存据此不读写(扩写词检索出的答案不能挂到原始短词键上)
        ShortQueryExpander.ExpandResult expanded =
                shortQueryExpander.expand(session.getSessionType(), retrievalQuery);
        if (expanded.expanded()) {
            retrievalQuery = expanded.query();
            rw = new QueryRewriter.RewriteResult(expanded.query(), true);
        }
        long rewriteMs = System.currentTimeMillis() - start;

        // ① 检索先行: 出口只能由检索事实算出。词表不再预判"要不要检索"(GENERAL 也查)——
        //    这是 P3-7 的核心: 知识库内容能否被问到, 不再取决于有没有人记得改一份与文档无关的配置。
        //    意图判定每轮只做一次(旧实现在缓存准入与路由两处各做一遍)。
        //    预判结果仍随决策日志留痕(rag_mode 列), 用于对照"词表预判 vs 实际出口"的偏差——
        // 成本优化 先进行关键字匹配是否要调用工具,要调用工具则少走一次调大模型进行向量化检索
        RagMode route = intentRouter.route(retrievalQuery);
        boolean toolTurn = route == RagMode.TOOL;
        long retrieveStart = System.currentTimeMillis();
        RagContext rag = resolveRagContext(session, retrievalQuery, route);
        long retrieveMs = System.currentTimeMillis() - retrieveStart;
        ChatOutcome outcome = rag.chatOutcome();

        // ② 语义缓存查询: 条目跨用户共享, 所以只在"确有知识库依据"或"确将拒答"的轮次参与
        boolean cacheEligible = cacheEligible(session, rw);
        long cacheStart = System.currentTimeMillis();
        // 正缓存查询
        SemanticAnswerCache.CachedAnswer cached = cacheEligible && outcome == ChatOutcome.ANSWERED_FROM_KB
                ? semanticAnswerCache.get(retrievalQuery) : null;
        // 负缓存(穿透防护)只可能出现在"本轮确实无据可依"时——检索先行已经知道有没有证据,
        // 绝不再让一条 30 分钟前的"没查到"覆盖刚查到的证据
        boolean missHit = cacheEligible && cached == null && outcome == ChatOutcome.REFUSED_NO_EVIDENCE
                && semanticAnswerCache.isMiss(retrievalQuery);
        long cacheMs = System.currentTimeMillis() - cacheStart;

        List<SourceVO> sources;
        AssembledPrompt assembled;
        if (cached != null) {
            // 正缓存命中: 跳过装配与模型调用; 检索事实保留在 rag.outcome() 里如实留痕
            // (executed=true 而注入 0 段), 出口改记 ANSWERED_FROM_CACHE
            rag = new RagContext(List.of(), rag.mode(), rag.outcome(), ChatOutcome.ANSWERED_FROM_CACHE);
            sources = List.of();
            assembled = null;
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start);
            // 命中分支同样打印各段耗时: 此处是"缓存本该秒回却变慢"的唯一现场
            log.info("语义缓存命中: session={}, question={}, 改写 {}ms, 检索+缓存查询 {}ms, 前置合计 {}ms",
                    session.getSessionId(), userMessage, rewriteMs,
                    System.currentTimeMillis() - start - rewriteMs, System.currentTimeMillis() - start);
        } else if (missHit) {
            // 负缓存命中: 无据可依的固定拒答文案, 省掉一次模型调用; 出口与真拒答一致
            cached = new SemanticAnswerCache.CachedAnswer(ChatSourceDisplay.NO_RESULT_ANSWER, List.of());
            sources = List.of();
            assembled = null;
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start);
            log.info("语义负缓存命中(无据可依短路): session={}, question={}, 改写 {}ms, 检索+缓存查询 {}ms, 前置合计 {}ms",
                    session.getSessionId(), userMessage, rewriteMs,
                    System.currentTimeMillis() - start - rewriteMs, System.currentTimeMillis() - start);
        } else {
            publishDecision(session, userMessage, rag, System.currentTimeMillis() - start - rewriteMs);
            sources = ragRetriever.toSources(rag.hits());
            // 装配提示词并进行 token计算
            long assembleStart = System.currentTimeMillis();
            assembled = contextAssembler.assemble(
                    session, userMessage, rag.hits(), rag.chatOutcome(), rw.rewritten());
            long assembleMs = System.currentTimeMillis() - assembleStart;
            // 分段耗时必须逐项打全(AGENTS.md 强制): 这是端点延迟标定与"慢在哪一段"的唯一现场
            log.info("对话前置阶段完成: session={}, 出口={}, 改写 {}ms, 检索 {}ms, 缓存查询 {}ms, 装配 {}ms, 合计 {}ms, 命中 {} 段",
                    session.getSessionId(), outcome, rewriteMs, retrieveMs, cacheMs, assembleMs,
                    System.currentTimeMillis() - start, rag.hits().size());
        }
        return new PreparedChat(rw, rag, sources, assembled, cacheEligible, retrievalQuery, cached,
                toolCalls);
    }

    /**
     * 判定本轮答案是否有资格参与语义缓存: 已启用缓存、问题未经过多轮改写(改写问题依赖会话上下文,
     * 缓存会串味)、非 AGENT 会话。
     *
     * <p>刻意<b>不再</b>在此判意图——出口 {@code ChatOutcome} 由调用方在检索之后与本题组合使用
     * (正缓存只服务 ANSWERED_FROM_KB, 负缓存只服务 REFUSED_NO_EVIDENCE)。
     *
     * @param session 会话
     * @param rw      查询改写结果
     * @return true=可尝试缓存
     */
    private boolean cacheEligible(ChatSession session, QueryRewriter.RewriteResult rw) {
        return appProperties.getSemanticCache().isEnabled()
                && !rw.rewritten()
                && session.getSessionType() != SessionType.AGENT;
    }

    /**
     * 检索并算出本轮出口：AGENT/非 RAG 会话不检索；工具轮跳过知识库检索(答案在业务库)；
     * 其余一律执行混合检索——**不再由关键词表预判"该不该查"**。
     *
     * @param session     会话
     * @param userMessage 检索问题
     * @param route       意图路由预判(只有 TOOL 影响链路, KB/GENERAL 作为审计留痕)
     * @return RAG 上下文快照(含出口)
     */
    private RagContext resolveRagContext(ChatSession session, String userMessage, RagMode route) {
        if (route == RagMode.TOOL) {
            // 工具类问题(查订单/物流)答案在业务库, 知识库检索必然查不到反而挤占上下文预算——
            // 跳过它是省一次 embedding, 不是判据; 出口记 TOOL_DATA, 严格模式下照样允许调工具
            log.debug("工具类问题, 跳过知识库检索(交给模型调工具): {}", userMessage);
            return new RagContext(List.of(), RagMode.TOOL, RetrievalOutcome.none(),
                    ChatOutcome.TOOL_DATA);
        }
        boolean ragSession = session.getSessionType() == SessionType.RAG
                || session.getSessionType() == SessionType.HYBRID;
        if (!ragSession) {
            return RagContext.empty();
        }
        // 混合检索
        RetrievalOutcome outcome = ragRetriever.retrieveOutcome(
                userMessage, appProperties.getRag().getTopK(),
                appProperties.getRag().getSimilarityThreshold());
        // 出口判定( 决定是由大模型自由发挥, 还是严格按照内部知识库/数据库作答)
        ChatOutcome chatOutcome = OutcomeResolver.resolve(outcome, false,
                appProperties.getChat().isKbOnly(), appProperties.getRag().getSimilarityThreshold());
        return new RagContext(outcome.hits(), route, outcome, chatOutcome);
    }

    /**
     * 发布"检索决策/出口"事件(异步写 rag_decision_log, 模型失败也留痕)。
     *
     * <p><b>审计口径变更(P3-7)</b>：旧约定"缓存命中 ⟺ {@code rag_mode=KB 且 retrieval_executed=false}"
     * 已作废——检索现在先于缓存查询执行，缓存命中时确实执行了检索。缓存命中改由一等公民
     * {@code answer_outcome=ANSWERED_FROM_CACHE} 标识（比原来的两列推断更直白，也不会被新分支破坏）。
     * 缓存命中轮次不装配上下文，因此<b>不产生 context_log</b> 这条约定保持不变。
     *
     * @param session     会话
     * @param userMessage 用户消息(截断 500 保存)
     * @param rag         RAG 上下文快照(含出口、路由模式与各路命中数)
     * @param costMs      决策与检索耗时
     */
    private void publishDecision(ChatSession session, String userMessage, RagContext rag, long costMs) {
        var outcome = rag.outcome() == null
                ? RetrievalOutcome.none() : rag.outcome();
        eventPublisher.publishEvent(new ChatDecisionEvent(
                session.getSessionId(), session.getUserId(),
                Strings.truncate(userMessage, 500),
                rag.mode().name(), rag.chatOutcome().name(), session.getSessionType().name(),
                // 是否执行检索 = 检索事实本身, 不能再与预判模式取与:
                // P3-7 起 GENERAL 也检索, 沿用"KB && executed"会把真实执行过的检索记成"未执行"
                outcome.executed(),
                outcome.semanticCount(), outcome.keywordCount(), rag.hits().size(),
                appProperties.getRag().getTopK(), appProperties.getRag().getSimilarityThreshold(),
                // 未执行检索(工具轮/AGENT 会话)时必须记 null, 不能记 0.0——
                // 否则定标时"没观察"会被当成"观察到了 0 分", 分数分布依旧失真
                outcome.executed() ? outcome.semanticMaxScore() : null,
                appProperties.getRag().getRerankMode(), costMs));
    }

    /**
     * 检索调试结果：命中标块 + <b>对话侧会判的出口</b>（同一判据、同一阈值语义）。
     *
     * @param retrievalQuery 实际用于检索的问题(可能经多轮改写或短查询扩展)
     * @param expanded       是否做过短查询扩展
     * @param outcome        检索明细(各路命中数/阈值过滤前最大分)
     * @param chatOutcome    把这轮交给对话作答时会命中的出口
     * @param topK           生效的 Top-K
     * @param threshold      生效的语义路余弦阈值
     */
    public record DebugSearch(String retrievalQuery, boolean expanded, RetrievalOutcome outcome,
                              ChatOutcome chatOutcome, int topK, double threshold) {
    }

    /**
     * 检索调试：走与对话<b>完全相同</b>的"扩展 → 混合检索 → 出口判定"三步，只回显不落地
     * (不写记忆/审计/缓存，也不装配提示词、不调用对话模型)。
     *
     * <p>与对话的唯一差别在这里：调试的目的就是"看看能召回什么"，所以工具类问题也照样检索
     * (对话里工具轮会跳过检索)，但出口判定仍按工具轮口径算，页面才能预测"对话会怎么答"。
     *
     * @param question   用户输入的查询
     * @param topK       召回条数; null 或 &lt;1 时取 {@code app.rag.top-k}
     * @param threshold  语义路余弦阈值; null 时取 {@code app.rag.similarity-threshold}(与对话一致)
     * @param expandShort 是否允许短查询扩展(默认与对话一致)
     * @return 调试结果
     */
    public DebugSearch debugSearch(String question, Integer topK, Double threshold, boolean expandShort) {
        int effectiveTopK = topK == null || topK < 1 ? appProperties.getRag().getTopK() : topK;
        double effectiveThreshold = threshold == null
                ? appProperties.getRag().getSimilarityThreshold() : threshold;
        String query = question == null ? "" : question.trim();
        boolean expanded = false;
        if (expandShort) {
            ShortQueryExpander.ExpandResult r = shortQueryExpander.expand(SessionType.HYBRID, query);
            if (r.expanded()) {
                query = r.query();
                expanded = true;
            }
        }
        RetrievalOutcome outcome = ragRetriever.retrieveOutcome(query, effectiveTopK, effectiveThreshold);
        boolean toolTurn = intentRouter.route(query) == RagMode.TOOL;
        ChatOutcome chatOutcome = OutcomeResolver.resolve(outcome, toolTurn,
                appProperties.getChat().isKbOnly(), effectiveThreshold);
        return new DebugSearch(query, expanded, outcome, chatOutcome, effectiveTopK, effectiveThreshold);
    }
}
