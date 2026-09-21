package com.ai.chat.service;

import com.ai.chat.event.ChatDecisionEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatPreparationService} 前置阶段单元测试：负缓存(穿透防护)命中时
 * 必须短路跳过检索与装配, 以固定"未找到"文案返回。
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
        service = new ChatPreparationService(queryRewriter, semanticAnswerCache, intentRouter,
                ragRetriever, contextAssembler, new AppProperties(), eventPublisher,
                sessionTitleService);
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
                ragRetriever, contextAssembler, props, eventPublisher, sessionTitleService);
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
}