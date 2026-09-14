package com.ai.context.service;

import com.ai.config.AppProperties;
import com.ai.context.ConversationMemory;
import com.ai.config.ChatClientProvider;
import com.ai.session.entity.ChatSession.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QueryRewriter} 单元测试：触发条件(首轮/AGENT/模型不可用跳过)、
 * 改写成功与回退、熔断(连续失败进入冷却→零等待跳过→成功复位)。
 */
class QueryRewriterTest {

    private static final String SID = "s1";

    private ConversationMemory memoryService;
    private AppProperties appProperties;
    private ChatClientProvider chatClientProvider;
    private CompressionQueryTransformer transformer;
    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        memoryService = mock(ConversationMemory.class);
        appProperties = new AppProperties();
        chatClientProvider = mock(ChatClientProvider.class);
        transformer = mock(CompressionQueryTransformer.class);
        rewriter = new QueryRewriter(memoryService, appProperties, chatClientProvider, transformer);
    }

    /** 有历史的会话(非首轮) */
    private void givenHistory() {
        when(memoryService.messageCount(SID)).thenReturn(2);
        when(memoryService.recentMessages(SID, 6)).thenReturn(List.of(new UserMessage("上一轮")));
    }

    private QueryRewriter.RewriteResult rewrite(String message) {
        return rewriter.rewrite(SID, SessionType.HYBRID, message);
    }

    @Test
    void skipsOnFirstTurn() {
        when(memoryService.messageCount(SID)).thenReturn(0);

        QueryRewriter.RewriteResult r = rewrite("年假有几天？");

        assertFalse(r.rewritten());
        assertEquals("年假有几天？", r.query());
        verify(transformer, times(0)).transform(any());
    }

    @Test
    void skipsForAgentSession() {
        QueryRewriter.RewriteResult r = rewriter.rewrite(SID, SessionType.AGENT, "年假有几天？");

        assertFalse(r.rewritten());
        verify(transformer, times(0)).transform(any());
    }

    @Test
    void compressesHistoryAndFollowUpIntoStandaloneQuery() {
        givenHistory();
        Query input = Query.builder().text("那他的邮箱呢？").build();
        when(transformer.transform(any())).thenReturn(new Query("张三的邮箱是什么？"));

        QueryRewriter.RewriteResult r = rewrite("那他的邮箱呢？");

        assertTrue(r.rewritten());
        assertEquals("张三的邮箱是什么？", r.query());
    }

    @Test
    void fallsBackWhenTransformerReturnsOriginalText() {
        givenHistory();
        when(transformer.transform(any())).thenReturn(new Query("那他的邮箱呢？"));

        QueryRewriter.RewriteResult r = rewrite("那他的邮箱呢？");

        assertFalse(r.rewritten());
        assertEquals("那他的邮箱呢？", r.query());
    }

    @Test
    void fallsBackWhenTransformerReturnsNull() {
        givenHistory();
        when(transformer.transform(any())).thenReturn(null);

        QueryRewriter.RewriteResult r = rewrite("那他的邮箱呢？");

        assertFalse(r.rewritten());
    }

    @Test
    void circuitBreakerOpensAfterTwoConsecutiveFailures() {
        appProperties.getContext().getQueryRewrite().setReferenceHintRequired(false);
        givenHistory();
        when(transformer.transform(any())).thenThrow(new RuntimeException("boom"));

        // 第一次失败: 正常告警降级
        assertFalse(rewrite("那病假呢？").rewritten());
        // 第二次失败: 触发熔断
        assertFalse(rewrite("那事假呢？").rewritten());
        // 熔断冷却期内: 零等待直接跳过, 不再调用 transformer
        QueryRewriter.RewriteResult r = rewrite("那调休呢？");
        assertFalse(r.rewritten());
        verify(transformer, times(2)).transform(any());
    }

    @Test
    void circuitBreakerResetsAfterSuccess() {
        appProperties.getContext().getQueryRewrite().setReferenceHintRequired(false);
        givenHistory();
        when(transformer.transform(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(new Query("改写后的问题"));

        assertFalse(rewrite("那病假呢？").rewritten());   // 失败 1
        assertFalse(rewrite("那事假呢？").rewritten());   // 失败 2 → 熔断
        assertFalse(rewrite("那调休呢？").rewritten());   // 冷却期跳过(未调 transformer)
        verify(transformer, times(2)).transform(any());
    }

    @Test
    void skipsWhenNoReferenceHint() {
        givenHistory(); // 默认 reference-hint-required=true

        QueryRewriter.RewriteResult r = rewrite("张三在哪个部门？"); // 完整问题, 无指代词

        assertFalse(r.rewritten());
        verify(transformer, times(0)).transform(any());
    }

    @Test
    void rewritesWhenReferenceHintPresent() {
        givenHistory();
        when(transformer.transform(any())).thenReturn(new Query("改写后的问题"));

        QueryRewriter.RewriteResult r = rewrite("那病假呢？"); // 含"那"

        assertTrue(r.rewritten());
        verify(transformer, times(1)).transform(any());
    }

    @Test
    void heuristicCanBeDisabled() {
        appProperties.getContext().getQueryRewrite().setReferenceHintRequired(false);
        givenHistory();
        when(transformer.transform(any())).thenReturn(new Query("改写后的问题"));

        QueryRewriter.RewriteResult r = rewrite("张三在哪个部门？"); // 无指代词也会改写

        assertTrue(r.rewritten());
    }

    @Test
    void modelUnavailableFallsBackWithoutTransformer() {
        when(chatClientProvider.getBuilderIfAvailable()).thenReturn(null);
        rewriter = new QueryRewriter(memoryService, appProperties, chatClientProvider);
        when(memoryService.messageCount(SID)).thenReturn(2);

        QueryRewriter.RewriteResult r = rewrite("那他的邮箱呢？");

        assertFalse(r.rewritten());
    }
}
