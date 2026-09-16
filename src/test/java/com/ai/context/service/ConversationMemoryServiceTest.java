package com.ai.context.service;

import com.ai.common.HeuristicTokenCounter;
import com.ai.config.AppProperties;
import com.ai.context.ConversationMemory;
import com.ai.context.HistoryContext;
import com.ai.context.entity.ConversationSummary;
import com.ai.context.mapper.ConversationSummaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ai.memory.ChatMemoryAppender;
import com.ai.memory.ChatMemoryCounter;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationMemoryService} 单元测试：
 * loadHistory 仅做窗口/预算收敛(不触发摘要)；滚动摘要在 summarizeIfNeededAsync 中
 * 按条数/Token 阈值触发, 成功后裁剪历史并落库摘要, 失败则保留现状。
 */
class ConversationMemoryServiceTest {

    private ChatMemoryRepository repository;
    private ChatMemoryAppender memoryAppender;
    private ChatMemoryCounter memoryCounter;
    private ConversationSummaryMapper summaryMapper;
    private ConversationSummarizer summarizer;
    private AppProperties appProperties;
    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMemoryRepository.class);
        memoryAppender = mock(ChatMemoryAppender.class);
        memoryCounter = mock(ChatMemoryCounter.class);
        summaryMapper = mock(ConversationSummaryMapper.class);
        summarizer = mock(ConversationSummarizer.class);
        appProperties = new AppProperties();
        service = new ConversationMemoryService(repository, memoryAppender, memoryCounter,
                summaryMapper, summarizer, new HeuristicTokenCounter(), appProperties);
    }

    @Test
    void messageCountUsesDatabaseCountInsteadOfLoadingMessages() {
        when(memoryCounter.countByConversationId("s1")).thenReturn(42L);

        assertEquals(42, service.messageCount("s1"));
        // 计数不再触碰消息加载路径(旧实现是 findByConversationId(...).size())
        verify(repository, never()).findByConversationId("s1");
    }

    /** 构造 n 条历史消息 */
    private List<Message> history(int n) {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            history.add(i % 2 == 0 ? new UserMessage("问题" + i) : new AssistantMessage("回答" + i));
        }
        return history;
    }

    @Test
    void loadHistoryKeepsMostRecentWithinTokenBudget() {
        when(repository.findByConversationId("s1")).thenReturn(history(10));
        when(summaryMapper.selectById("s1")).thenReturn(null);

        HistoryContext ctx = service.loadHistory("s1", 20);

        assertTrue(ctx.messages().size() < 10);
        assertTrue(ctx.truncated());
        assertTrue(ctx.summaryTokens() == 0);
        // 读路径不再触发摘要
        verify(summarizer, never()).summarize(any(), anyList());
    }

    @Test
    void loadHistoryDoesNotSummarizeEvenOverThreshold() {
        appProperties.getContext().getSummary().setTriggerMessages(4);
        when(repository.findByConversationId("s1")).thenReturn(history(10));
        when(summaryMapper.selectById("s1")).thenReturn(null);

        service.loadHistory("s1", 10000);

        verify(summarizer, never()).summarize(any(), anyList());
        verify(repository, never()).saveAll(any(), anyList());
    }

    @Test
    void asyncSummaryTriggersByCountAndPrunesHistory() {
        appProperties.getContext().getSummary().setTriggerMessages(4);
        appProperties.getContext().getSummary().setKeepRecentMessages(2);
        when(repository.findByConversationId("s1")).thenReturn(history(6));
        when(summaryMapper.selectById("s1")).thenReturn(null);
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new ConversationSummarizer.SummaryResult("新摘要", true));

        service.summarizeIfNeededAsync("s1");

        // 合并既有摘要(空)与老消息(前 4 条)生成新摘要并落库
        verify(summarizer).summarize(eq(null), argThat(list -> list.size() == 4));
        verify(summaryMapper).insert(any(ConversationSummary.class));
        // 裁剪: 仅保留最近 2 条
        verify(repository).saveAll(eq("s1"), argThat(list -> list.size() == 2));
    }

    @Test
    void asyncSummaryMergesExistingSummaryOnSecondRun() {
        appProperties.getContext().getSummary().setTriggerMessages(4);
        appProperties.getContext().getSummary().setKeepRecentMessages(2);
        when(repository.findByConversationId("s1")).thenReturn(history(6));
        ConversationSummary existing = new ConversationSummary();
        existing.setConversationId("s1");
        existing.setSummaryText("旧摘要");
        when(summaryMapper.selectById("s1")).thenReturn(existing);
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new ConversationSummarizer.SummaryResult("合并后的新摘要", true));

        service.summarizeIfNeededAsync("s1");

        // 二次摘要应把"旧摘要"作为入参合并
        verify(summarizer).summarize(eq("旧摘要"), anyList());
        verify(summaryMapper).updateById(any(ConversationSummary.class));
        verify(summaryMapper, never()).insert(any(ConversationSummary.class));
    }

    @Test
    void asyncSummaryKeepsHistoryWhenLlmFails() {
        appProperties.getContext().getSummary().setTriggerMessages(4);
        when(repository.findByConversationId("s1")).thenReturn(history(6));
        when(summaryMapper.selectById("s1")).thenReturn(null);
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(ConversationSummarizer.SummaryResult.unchanged(null));

        service.summarizeIfNeededAsync("s1");

        // 摘要失败: 不裁剪、不落库
        verify(repository, never()).saveAll(any(), anyList());
        verify(summaryMapper, never()).insert(any(ConversationSummary.class));
    }

    @Test
    void asyncSummarySkippedBelowThreshold() {
        when(repository.findByConversationId("s1")).thenReturn(history(2));
        when(summaryMapper.selectById("s1")).thenReturn(null);

        service.summarizeIfNeededAsync("s1");

        verify(summarizer, never()).summarize(any(), anyList());
    }

    @Test
    void asyncSummaryTriggersByTokenThreshold() {
        appProperties.getContext().getSummary().setTriggerMessages(100); // 条数不触发
        appProperties.getContext().getSummary().setTriggerTokens(30);   // Token 阈值触发
        when(repository.findByConversationId("s1")).thenReturn(history(10));
        when(summaryMapper.selectById("s1")).thenReturn(null);
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new ConversationSummarizer.SummaryResult("token 触发的摘要", true));

        service.summarizeIfNeededAsync("s1");

        verify(summarizer).summarize(any(), anyList());
        verify(repository).saveAll(eq("s1"), anyList());
    }
}
