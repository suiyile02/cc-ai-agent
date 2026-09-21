package com.ai.context.service;
import com.ai.context.AssembledPrompt;
import com.ai.context.ContextComposition;
import com.ai.context.HistoryContext;

import com.ai.common.HeuristicTokenCounter;
import com.ai.prompt.PromptService;
import com.ai.config.AppProperties;
import com.ai.session.SessionType;
import com.ai.session.entity.ChatSession;
import com.ai.rag.ChatOutcome;
import com.ai.rag.RagMode;
import com.ai.rag.service.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContextAssembler} 单元测试：预算切分、Token 组成计量、消息装配。
 */
class ContextAssemblerTest {

    private RagRetrievalService ragRetrievalService;
    private PromptService promptService;
    private ConversationMemoryService memoryService;
    private AppProperties appProperties;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ragRetrievalService = mock(RagRetrievalService.class);
        promptService = mock(PromptService.class);
        memoryService = mock(ConversationMemoryService.class);
        appProperties = new AppProperties();
        assembler = new ContextAssembler(ragRetrievalService, promptService, memoryService,
                new HeuristicTokenCounter(), appProperties);
    }

    private ChatSession session() {
        ChatSession s = new ChatSession();
        s.setSessionId("sess-1");
        s.setSessionType(SessionType.HYBRID);
        return s;
    }

    @Test
    void assembleSplitsBudgetAndBuildsComposition() {
        when(memoryService.loadHistory(eq("sess-1"), anyInt()))
                .thenReturn(new HistoryContext(
                        null, List.of(new UserMessage("你好")), 0, 6, false));
        when(ragRetrievalService.buildContext(anyList(), anyInt())).thenReturn("年假五天");
        when(promptService.systemFor(any(), any(), anyBoolean(), anyString()))
                .thenReturn("系统提示 年假五天");

        AssembledPrompt ap = assembler.assemble(session(), "年假几天",
                List.of(Document.builder().text("年假五天").build()), ChatOutcome.ANSWERED_FROM_KB, false);

        assertNotNull(ap);
        ContextComposition c = ap.composition();
        assertEquals(c.systemTokens() + c.ragTokens() + c.historyTokens() + c.userTokens(),
                c.totalTokens());
        assertEquals(8192, c.modelMaxTokens());
        assertEquals(1, c.historyMessages());
        assertEquals("年假几天", ap.queryForModel());
        // ragBudget = (8192-1500)*0.40 = 2676
        verify(ragRetrievalService).buildContext(anyList(), eq(2676));
        // historyBudget = (8192-1500)*0.35 = 2342
        verify(memoryService).loadHistory("sess-1", 2342);
    }

    @Test
    void injectsSummaryAsSystemMessage() {
        when(memoryService.loadHistory(eq("sess-1"), anyInt()))
                .thenReturn(new HistoryContext(
                        "早前讨论过年假", List.of(new UserMessage("你好")), 12, 18, false));
        when(ragRetrievalService.buildContext(anyList(), anyInt())).thenReturn("");
        when(promptService.systemFor(any(), any(), anyBoolean(), anyString())).thenReturn("系统提示");

        AssembledPrompt ap = assembler.assemble(session(), "你好", List.of(), ChatOutcome.ANSWERED_OPEN, false);

        // 首条应为摘要 SYSTEM 消息, 其后为历史
        assertEquals(2, ap.messages().size());
        assertEquals(org.springframework.ai.chat.messages.MessageType.SYSTEM,
                ap.messages().get(0).getMessageType());
        assertEquals(12, ap.composition().summaryTokens());
    }
}
