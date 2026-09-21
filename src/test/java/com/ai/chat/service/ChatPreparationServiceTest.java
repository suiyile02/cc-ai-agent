package com.ai.chat.service;

import com.ai.chat.event.ChatDecisionEvent;
import com.ai.config.AppProperties;
import com.ai.context.AssembledPrompt;
import com.ai.context.service.ContextAssembler;
import com.ai.context.service.QueryRewriter;
import com.ai.context.service.ShortQueryExpander;
import com.ai.rag.ChatOutcome;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import com.ai.session.SessionType;
import com.ai.session.service.SessionTitleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatPreparationService} 前置阶段单元测试：检索先行的出口判定、缓存准入闸门
 * (正/负缓存只在"确有依据"或"确将拒答"时参与)、决策事件字段口径、短查询扩展对缓存准入的影响。
 */
class ChatPreparationServiceTest {

    private static final String QUESTION = "加班和调休是怎么安排的？";

    private QueryRewriter queryRewriter;
    private SemanticAnswerCache semanticAnswerCache;
    private IntentRouter intentRouter;
    private RagRetriever ragRetriever;
    private ContextAssembler contextAssembler;
    private ApplicationEventPublisher eventPublisher;
    private SessionTitleService sessionTitleService;
    private ShortQueryExpander shortQueryExpander;
    private ChatPreparationService service;

    @BeforeEach
    void setUp() {
        queryRewriter = mock(QueryRewriter.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        intentRouter = mock(IntentRouter.class);
        ragRetriever = mock(RagRetriever.class);
        contextAssembler = mock(ContextAssembler.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sessionTitleService = mock(SessionTitleService.class);
        // 默认不扩展(各用例按需改写), 避免每个用例都要桩一层模型调用
        shortQueryExpander = mock(ShortQueryExpander.class);
        lenient().when(shortQueryExpander.expand(any(), anyString())).thenAnswer(inv ->
                new ShortQueryExpander.ExpandResult(inv.getArgument(1), false));
        service = new ChatPreparationService(queryRewriter, semanticAnswerCache, intentRouter,
                ragRetriever, contextAssembler, new AppProperties(), eventPublisher,
                sessionTitleService, shortQueryExpander);
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setSessionId("s1");
        session.setUserId(9L);
        session.setSessionType(SessionType.HYBRID);
        session.setStatus(1);
        return session;
    }

    /** 未改写 + KB 路由 + 缓存启用 → 可参与缓存的基线 */
    private void givenCacheEligibleBaseline() {
        when(queryRewriter.rewrite(anyString(), any(), anyString()))
                .thenReturn(new QueryRewriter.RewriteResult(QUESTION, false));
        when(intentRouter.route(anyString())).thenReturn(RagMode.KB);
        when(semanticAnswerCache.get(anyString())).thenReturn(null);
    }

    /**
     * P3-7 改序后的负缓存语义: 检索**先**执行(出口只能由检索事实算出),
     * 负缓存的作用是"省掉一次注定拒答的模型调用", 不再是"跳过检索"。
     */
    @Test
    void negativeCacheSavesTheModelCallButNotTheRetrieval() {
        givenCacheEligibleBaseline();
        // 本轮检索执行了但无语义证据(默认 kb-only=true → 出口 REFUSED_NO_EVIDENCE)
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 10, false, 0.31));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(true);

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertNotNull(prep.cachedAnswer());
        assertEquals(ChatSourceDisplay.NO_RESULT_ANSWER, prep.cachedAnswer().content());
        assertTrue(prep.cachedAnswer().sources().isEmpty());
        assertNull(prep.assembled(), "负缓存命中不装配上下文");
        verify(semanticAnswerCache).isMiss(QUESTION);
        // 与改造前唯一的区别: 检索必须真的跑过——出口不能靠猜
        verify(ragRetriever).retrieveOutcome(anyString(), anyInt(), anyDouble());
        verify(contextAssembler, never()).assemble(any(), anyString(), anyList(), any(), anyBoolean());
        verify(eventPublisher).publishEvent(any(ChatDecisionEvent.class));
    }

    @Test
    void negativeCacheMissRunsFullRetrieval() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false, 0.0));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertNull(prep.cachedAnswer());
        verify(semanticAnswerCache).isMiss(QUESTION);
        verify(ragRetriever).retrieveOutcome(anyString(), anyInt(), anyDouble());
        verify(contextAssembler).assemble(any(), anyString(), anyList(), any(), anyBoolean());
    }

    @Test
    void disabledCacheSkipsMissCheck() {
        AppProperties props = new AppProperties();
        props.getSemanticCache().setEnabled(false);
        service = new ChatPreparationService(queryRewriter, semanticAnswerCache, intentRouter,
                ragRetriever, contextAssembler, props, eventPublisher, sessionTitleService,
                shortQueryExpander);
        givenCacheEligibleBaseline();
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false, 0.0));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertNull(prep.cachedAnswer());
        verify(semanticAnswerCache, never()).isMiss(anyString());
    }

    @Test
    void toolQuestionSkipsRetrievalAndMarksTool() {
        when(queryRewriter.rewrite(anyString(), any(), anyString()))
                .thenReturn(new QueryRewriter.RewriteResult(QUESTION, false));
        when(intentRouter.route(anyString())).thenReturn(RagMode.TOOL);
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertEquals(RagMode.TOOL, prep.rag().mode(), "工具类问题审计应标记为 TOOL");
        assertTrue(prep.rag().hits().isEmpty(), "工具类问题不得返回检索命中");
        // 工具类问题跳过检索与缓存(答案在业务库, 不在知识库)
        verify(ragRetriever, never()).retrieveOutcome(anyString(), anyInt(), anyDouble());
        verify(semanticAnswerCache, never()).get(anyString());
        // 决策审计照常发布(rag_mode=TOOL)
        verify(eventPublisher).publishEvent(any(ChatDecisionEvent.class));
    }

    @Test
    void firstTurnTriggersTitleFallbackAndRefine() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false, 0.0));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));
        when(sessionTitleService.claimFallback(any(), anyString())).thenReturn("加班和调休…");

        service.prepare(session(), QUESTION);

        // 抢到首轮 → 必须提交精修, 否则标题永远停在截断版
        verify(sessionTitleService).refineAsync("s1", QUESTION, "加班和调休…");
    }

    @Test
    void sessionWithExistingTitleSkipsRefine() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false, 0.0));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));
        when(sessionTitleService.claimFallback(any(), anyString())).thenReturn(null);

        service.prepare(session(), QUESTION);

        verify(sessionTitleService, never()).refineAsync(anyString(), anyString(), anyString());
    }

    @Test
    void skippedRetrievalRecordsNullMaxScoreNotZero() {
        // 工具类问题跳过检索: 决策事件里的 semanticMaxScore 必须是 null("没观察"),
        // 不能是 0.0("观察到 0 分")——否则 P3-6 用它统计分数分布依然失真
        when(queryRewriter.rewrite(anyString(), any(), anyString()))
                .thenReturn(new QueryRewriter.RewriteResult(QUESTION, false));
        when(intentRouter.route(anyString())).thenReturn(RagMode.TOOL);
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        service.prepare(session(), QUESTION);

        ArgumentCaptor<ChatDecisionEvent> sent = ArgumentCaptor.forClass(ChatDecisionEvent.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertNull(sent.getValue().semanticMaxScore(), "未执行检索时语义最高分应为 null");
        assertFalse(sent.getValue().retrievalExecuted());
    }

    @Test
    void executedRetrievalRecordsObservedMaxScore() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 3, 10, false, 0.72));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        service.prepare(session(), QUESTION);

        ArgumentCaptor<ChatDecisionEvent> sent = ArgumentCaptor.forClass(ChatDecisionEvent.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertEquals(0.72, sent.getValue().semanticMaxScore(), 1e-9, "执行了检索就要带上过滤前最大分");
    }

    /**
     * P3-7 B 批回归: GENERAL 预判轮次同样真的执行了检索, `retrieval_executed` 必须记 true。
     * 旧实现写的是 "rag_mode==KB && executed"(预判时代的遗留), 会把 GENERAL 轮次的真实检索
     * 记成"未执行"——审计里表现为"有分数、有命中, 却没检索过"的自相矛盾行。
     */
    @Test
    void generalRouteStillRecordsRetrievalAsExecuted() {
        when(queryRewriter.rewrite(anyString(), any(), anyString()))
                .thenReturn(new QueryRewriter.RewriteResult(QUESTION, false));
        when(intentRouter.route(anyString())).thenReturn(RagMode.GENERAL);
        when(semanticAnswerCache.get(anyString())).thenReturn(null);
        when(semanticAnswerCache.isMiss(anyString())).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 10, false, 0.2046));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        service.prepare(session(), QUESTION);

        ArgumentCaptor<ChatDecisionEvent> sent = ArgumentCaptor.forClass(ChatDecisionEvent.class);
        verify(eventPublisher).publishEvent(sent.capture());
        assertEquals("GENERAL", sent.getValue().ragMode(), "预判要如实留痕, 供与出口对照评估词表猜错率");
        assertTrue(sent.getValue().retrievalExecuted(), "GENERAL 轮次的检索同样已执行");
        assertEquals(0.2046, sent.getValue().semanticMaxScore(), 1e-9);
    }

    /**
     * 短查询扩展成功的轮次必须"缓存不合格": 缓存条目跨用户共享,
     * 用扩写词检索出的答案挂到原始短词键上, 后来者会拿到跑题内容。
     */
    @Test
    void expandedQueryBarsSemanticCache() {
        String expanded = "产品的定价和套餐有哪些规定";
        when(queryRewriter.rewrite(anyString(), any(), anyString()))
                .thenReturn(new QueryRewriter.RewriteResult(QUESTION, false));
        when(shortQueryExpander.expand(any(), anyString()))
                .thenReturn(new ShortQueryExpander.ExpandResult(expanded, true));
        when(intentRouter.route(anyString())).thenReturn(RagMode.KB);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(mock(org.springframework.ai.document.Document.class)),
                        true, 2, 5, false, 0.72));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        ChatPreparationService.PreparedChat prep = service.prepare(session(), "产品");

        assertTrue(prep.rag().chatOutcome() == ChatOutcome.ANSWERED_FROM_KB, "扩展后过阈值, 判有据作答");
        assertFalse(prep.cacheEligible(), "扩展轮不得读写语义缓存");
        verify(ragRetriever).retrieveOutcome(eq(expanded), anyInt(), anyDouble());
        verify(semanticAnswerCache, never()).get(anyString());
    }

    /** 调试接口缺省口径必须与对话一致: 阈值/TopK 取配置值, 出口按同一判据算 */
    @Test
    void debugSearchFollowsChatDefaults() {
        AppProperties props = new AppProperties();
        service = new ChatPreparationService(queryRewriter, semanticAnswerCache, intentRouter,
                ragRetriever, contextAssembler, props, eventPublisher, sessionTitleService,
                shortQueryExpander);
        when(intentRouter.route(anyString())).thenReturn(RagMode.GENERAL);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 5, false, 0.4097));

        ChatPreparationService.DebugSearch debug = service.debugSearch("产品", null, null, false);

        assertEquals(props.getRag().getTopK(), debug.topK());
        assertEquals(props.getRag().getSimilarityThreshold(), debug.threshold(), 1e-9,
                "留空即跟随对话阈值, 不再暗置 0(不过滤)——那会造出第三种口径");
        assertEquals(ChatOutcome.REFUSED_NO_EVIDENCE, debug.chatOutcome(),
                "关键词有命中但向量未过阈值 → 对话会拒答, 页面须如实预测");
        assertFalse(debug.expanded());
        verify(shortQueryExpander, never()).expand(any(), anyString());
    }

    /** 工具轮: 页面照样检索(目的就是看召回), 但出口预测须与对话一致记 TOOL_DATA */
    @Test
    void debugSearchPredictsToolTurnAndReportsExpandedQuery() {
        when(shortQueryExpander.expand(any(), anyString()))
                .thenReturn(new ShortQueryExpander.ExpandResult("订单的物流状态是什么", true));
        when(intentRouter.route("订单的物流状态是什么")).thenReturn(RagMode.TOOL);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 1, 3, false, 0.51));

        ChatPreparationService.DebugSearch debug = service.debugSearch("订单", 5, 0.45, true);

        assertTrue(debug.expanded());
        assertEquals("订单的物流状态是什么", debug.retrievalQuery());
        assertEquals(ChatOutcome.TOOL_DATA, debug.chatOutcome());
        verify(ragRetriever).retrieveOutcome("订单的物流状态是什么", 5, 0.45);
    }
}