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
import com.ai.session.entity.ChatSession.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private ChatPreparationService service;

    @BeforeEach
    void setUp() {
        queryRewriter = mock(QueryRewriter.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        intentRouter = mock(IntentRouter.class);
        ragRetriever = mock(RagRetriever.class);
        contextAssembler = mock(ContextAssembler.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ChatPreparationService(queryRewriter, semanticAnswerCache, intentRouter,
                ragRetriever, contextAssembler, new AppProperties(), eventPublisher);
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

    @Test
    void negativeCacheHitShortCircuitsRetrievalAndAssembly() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(true);

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertNotNull(prep.cachedAnswer());
        assertEquals(ChatSourceDisplay.NO_RESULT_ANSWER, prep.cachedAnswer().content());
        assertTrue(prep.cachedAnswer().sources().isEmpty());
        assertNull(prep.assembled(), "负缓存命中不装配上下文");
        verify(semanticAnswerCache).isMiss(QUESTION);
        verify(ragRetriever, never()).retrieveOutcome(anyString(), anyInt(), anyDouble());
        verify(contextAssembler, never()).assemble(any(), anyString(), anyList(), any(), anyBoolean());
        verify(eventPublisher).publishEvent(any(ChatDecisionEvent.class));
    }

    @Test
    void negativeCacheMissRunsFullRetrieval() {
        givenCacheEligibleBaseline();
        when(semanticAnswerCache.isMiss(QUESTION)).thenReturn(false);
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false));
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
                ragRetriever, contextAssembler, props, eventPublisher);
        givenCacheEligibleBaseline();
        when(ragRetriever.retrieveOutcome(anyString(), anyInt(), anyDouble()))
                .thenReturn(new RetrievalOutcome(List.of(), true, 0, 0, false));
        when(ragRetriever.toSources(anyList())).thenReturn(List.of());
        when(contextAssembler.assemble(any(), anyString(), anyList(), any(), anyBoolean()))
                .thenReturn(mock(AssembledPrompt.class));

        ChatPreparationService.PreparedChat prep = service.prepare(session(), QUESTION);

        assertNull(prep.cachedAnswer());
        verify(semanticAnswerCache, never()).isMiss(anyString());
    }
}