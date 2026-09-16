package com.ai.chat.service;

import com.ai.config.AppProperties;
import com.ai.context.ConversationMemory;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.RagMode;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatCompletionService} 收尾阶段单元测试：语义缓存写入条件——
 * 正缓存(有命中且未声明未找到) / 负缓存(检索执行且零命中, 排除超时降级)。
 */
class ChatCompletionServiceTest {

    private static final String QUESTION = "加班和调休是怎么安排的？";
    private static final String SID = "s1";

    private ConversationMemory memoryService;
    private SemanticAnswerCache semanticAnswerCache;
    private ChatCompletionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        memoryService = mock(ConversationMemory.class);
        semanticAnswerCache = mock(SemanticAnswerCache.class);
        ObjectProvider<ChatModel> chatModelProvider = mock(ObjectProvider.class);
        when(chatModelProvider.getIfAvailable()).thenReturn(null);
        service = new ChatCompletionService(memoryService, semanticAnswerCache,
                mock(ApplicationEventPublisher.class), chatModelProvider, new AppProperties());
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setSessionId(SID);
        session.setUserId(9L);
        return session;
    }

    private ChatPreparationService.PreparedChat prep(RetrievalOutcome outcome,
            List<com.ai.chat.dto.SourceVO> sources) {
        return new ChatPreparationService.PreparedChat(
                new QueryRewriter.RewriteResult(QUESTION, false),
                new ChatPreparationService.RagContext(outcome.hits(), RagMode.KB, outcome),
                sources, null, true, QUESTION, null);
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
        List<com.ai.chat.dto.SourceVO> sources =
                List.of(new com.ai.chat.dto.SourceVO("员工手册.md", 4L, 0, "片段", 0.6));
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
                List.of(), null, false, QUESTION, null);
        service.complete(session(), "用户问题", notEligible, "随便聊聊", 10, 0L);

        verify(semanticAnswerCache, never()).put(anyString(), anyString(), any());
        verify(semanticAnswerCache, never()).putMiss(anyString());
    }
}