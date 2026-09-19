package com.ai.chat.service;

import com.ai.config.ChatClientProvider;
import com.ai.context.ConversationMemory;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.RagMode;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatCompletionService} 收尾阶段单元测试：语义缓存写入条件——
 * 正缓存(有命中且未声明未找到) / 负缓存(检索执行且零命中, 排除超时降级) /
 * 工具轮次(本轮调用过工具, 答案含业务库实时数据)一律不写。
 */
class ChatCompletionServiceTest {

    private static final String QUESTION = "加班和调休是怎么安排的？";
    private static final String SID = "s1";

    private ConversationMemory memoryService;
    private SemanticAnswerCache semanticAnswerCache;
    private ChatCompletionService service;

    @BeforeEach
    void setUp() {
        memoryService = mock(ConversationMemory.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        ChatClientProvider chatClientProvider = mock(ChatClientProvider.class);
        lenient().when(chatClientProvider.modelLabel()).thenReturn("qwen-test");
        service = new ChatCompletionService(memoryService, semanticAnswerCache,
                mock(ApplicationEventPublisher.class), chatClientProvider);
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setSessionId(SID);
        session.setUserId(9L);
        return session;
    }

    private ChatPreparationService.PreparedChat prep(RetrievalOutcome outcome,
            List<com.ai.rag.SourceVO> sources) {
        return prep(outcome, sources, new AtomicInteger());
    }

    /**
     * 构造前置阶段结果。
     *
     * @param outcome   检索结果
     * @param sources   来源列表
     * @param toolCalls 本轮工具调用计数容器
     * @return 前置结果(KB 路由 + 可缓存)
     */
    private ChatPreparationService.PreparedChat prep(RetrievalOutcome outcome,
            List<com.ai.rag.SourceVO> sources, AtomicInteger toolCalls) {
        return new ChatPreparationService.PreparedChat(
                new QueryRewriter.RewriteResult(QUESTION, false),
                new ChatPreparationService.RagContext(outcome.hits(), RagMode.KB, outcome),
                sources, null, true, QUESTION, null, toolCalls);
    }

    @Test
    void zeroHitNonDegradedWritesNegativeCache() {
        service.complete(session(), "用户问题",
                prep(new RetrievalOutcome(List.of(), true, 0, 0, false), List.of()),
                "知识库中未找到相关信息。", 10, 0L);

        verify(semanticAnswerCache).putMiss(QUESTION);
        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
    }

    @Test
    void degradedZeroHitDoesNotWriteNegativeCache() {
        service.complete(session(), "用户问题",
                prep(RetrievalOutcome.executedEmpty(), List.of()),
                "知识库中未找到相关信息。", 10, 0L);

        verify(semanticAnswerCache, never()).putMiss(anyString());
        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
    }

    @Test
    void positiveHitsWritePositiveCache() {
        Document doc = Document.builder().text("调休相关内容").build();
        List<com.ai.rag.SourceVO> sources =
                List.of(new com.ai.rag.SourceVO("员工手册.md", 4L, 0, "片段", 0.6));
        service.complete(session(), "用户问题",
                prep(new RetrievalOutcome(List.of(doc), true, 1, 0, false), sources),
                "调休按 1:1 计算。", 10, 0L);

        verify(semanticAnswerCache).put(eq(QUESTION), eq("调休按 1:1 计算。"),
                eq(List.of("员工手册")));
        verify(semanticAnswerCache, never()).putMiss(anyString());
    }

    @Test
    void nonCacheEligibleWritesNothing() {
        ChatPreparationService.PreparedChat notEligible = new ChatPreparationService.PreparedChat(
                new QueryRewriter.RewriteResult(QUESTION, false),
                new ChatPreparationService.RagContext(List.of(), RagMode.GENERAL,
                        RetrievalOutcome.none()),
                List.of(), null, false, QUESTION, null, new AtomicInteger());
        service.complete(session(), "用户问题", notEligible, "随便聊聊", 10, 0L);

        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
        verify(semanticAnswerCache, never()).putMiss(anyString());
    }

    /**
     * 工具轮次不得写正缓存：缓存条目跨用户共享, 而工具答案含业务库实时数据(员工联系方式/订单状态)。
     */
    @Test
    void toolCallTurnDoesNotWritePositiveCache() {
        Document doc = Document.builder().text("员工手册相关内容").build();
        List<com.ai.rag.SourceVO> sources =
                List.of(new com.ai.rag.SourceVO("员工手册.md", 4L, 0, "片段", 0.6));
        AtomicInteger toolCalls = new AtomicInteger(1);

        service.complete(session(), "用户问题",
                prep(new RetrievalOutcome(List.of(doc), true, 1, 0, false), sources, toolCalls),
                "张三在研发部, 电话 13800000000。", 10, 0L);

        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
        verify(semanticAnswerCache, never()).putMiss(anyString());
    }

    /**
     * 工具轮次同样不得写负缓存：问题可能已由工具作答, 标成"无答案"会让后续同问被短路 30 分钟。
     */
    @Test
    void toolCallTurnDoesNotWriteNegativeCache() {
        service.complete(session(), "用户问题",
                prep(new RetrievalOutcome(List.of(), true, 0, 0, false), List.of(),
                        new AtomicInteger(2)),
                "订单已发货。", 10, 0L);

        verify(semanticAnswerCache, never()).putMiss(anyString());
        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
    }
}