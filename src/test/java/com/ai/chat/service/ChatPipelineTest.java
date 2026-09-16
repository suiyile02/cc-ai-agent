package com.ai.chat.service;

import com.ai.agent.BusinessTools;
import com.ai.chat.dto.ChatStreamEvent;
import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.context.AssembledPrompt;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.RagMode;
import com.ai.rag.RetrievalOutcome;
import com.ai.rag.service.SemanticAnswerCache;
import com.ai.session.entity.ChatSession;
import com.ai.session.entity.ChatSession.SessionType;
import com.ai.session.service.ChatSessionService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话管线特征化测试：锁定同步/流式两条管线在"语义缓存命中/未命中"下的对外行为,
 * 作为 ChatService 拆分重构的回归网。
 */
class ChatPipelineTest {

    private static final String SID = "s1";
    private static final Long UID = 2L;
    private static final String QUESTION = "加班和调休是怎么安排的？";

    private ChatSessionService sessionService;
    private ChatPreparationService preparation;
    private ChatCompletionService completion;
    private ChatConcurrencyGuard concurrencyGuard;
    private ChatClientProvider chatClientProvider;
    private ChatConcurrencyGuard.Handle guardHandle;
    private AppProperties appProperties;
    private ChatService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(ChatSessionService.class);
        preparation = mock(ChatPreparationService.class);
        completion = mock(ChatCompletionService.class);
        concurrencyGuard = mock(ChatConcurrencyGuard.class);
        chatClientProvider = mock(ChatClientProvider.class);
        guardHandle = mock(ChatConcurrencyGuard.Handle.class);
        Mockito.lenient().when(concurrencyGuard.acquire(any())).thenReturn(guardHandle);
        assembled = mock(AssembledPrompt.class);
        // 注意: assembled 的 stub 必须在 setUp 完成, 不能在 when(...) 参数求值期间嵌套 stubbing
        Mockito.lenient().when(assembled.system()).thenReturn("system-prompt");
        Mockito.lenient().when(assembled.messages()).thenReturn(List.of());
        Mockito.lenient().when(assembled.queryForModel()).thenReturn(QUESTION);
        Mockito.lenient().when(sessionService.requireActive(any(), any())).thenReturn(session());

        appProperties = new AppProperties();
        service = new ChatService(sessionService, preparation, completion, concurrencyGuard,
                chatClientProvider, mock(com.ai.rag.RagRetriever.class), mock(BusinessTools.class),
                appProperties, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private AssembledPrompt assembled;

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setSessionId(SID);
        session.setUserId(UID);
        session.setSessionType(SessionType.HYBRID);
        session.setStatus(1);
        return session;
    }

    private ChatPreparationService.PreparedChat hitPrep() {
        return new ChatPreparationService.PreparedChat(
                new QueryRewriter.RewriteResult(QUESTION, false),
                new ChatPreparationService.RagContext(List.of(), RagMode.KB, RetrievalOutcome.none()),
                List.of(), null, true, QUESTION,
                new SemanticAnswerCache.CachedAnswer("缓存答案", List.of("员工手册")));
    }

    private ChatPreparationService.PreparedChat missPrep() {
        return new ChatPreparationService.PreparedChat(
                new QueryRewriter.RewriteResult(QUESTION, false),
                new ChatPreparationService.RagContext(
                        List.of(new Document("调休相关内容")), RagMode.KB, RetrievalOutcome.none()),
                List.of(new com.ai.chat.dto.SourceVO("员工手册.md", 4L, 0, "片段", 0.6)),
                assembled, true, QUESTION, null);
    }

    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;

    @SuppressWarnings("unchecked")
    private void givenModelAnswers(String answer) {
        ChatClient client = mock(ChatClient.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(client);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(anyList())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.tools(any(BusinessTools.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        // 注意: 先构建响应对象再放进 when(), 避免 stub 期间嵌套操作其它 mock
        ChatResponse callResponse = responseWith(answer, 100);
        when(callSpec.chatResponse()).thenReturn(callResponse);
        when(spec.stream()).thenReturn(streamSpec);
        ChatResponse s1 = responseWith("你", 0);
        ChatResponse s2 = responseWith("好", 50);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(s1, s2));
    }

    private ChatResponse responseWith(String text, int usage) {
        // AssistantMessage.getText() 为 final, 用真实对象
        AssistantMessage output = new AssistantMessage(text);
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(output);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(generation);
        if (usage > 0) {
            Usage usageMeta = mock(Usage.class);
            when(usageMeta.getTotalTokens()).thenReturn(usage);
            ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
            when(metadata.getUsage()).thenReturn(usageMeta);
            when(resp.getMetadata()).thenReturn(metadata);
        }
        return resp;
    }

    @Test
    void syncCacheHitReturnsCachedAnswerWithoutModelCall() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(hitPrep());
        when(chatClientProvider.getIfAvailable()).thenReturn(null); // 模型不可用也能命中缓存

        com.ai.chat.dto.ChatResponse r = service.chat(SID, QUESTION, UID);

        assertEquals("缓存答案", r.content());
        assertEquals(List.of("员工手册"), r.sources());
        verify(completion).completeCached(any(), eq(QUESTION), any(), anyLong());
        verify(completion, never()).complete(any(), anyString(), any(), anyString(), any(), anyLong());
    }

    @Test
    void syncMissGeneratesAnswerAndStoresCache() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(missPrep());
        givenModelAnswers("生成回答");
        when(completion.complete(any(), eq(QUESTION), any(), eq("生成回答"), eq(100), anyLong()))
                .thenReturn(List.of("员工手册"));

        com.ai.chat.dto.ChatResponse r = service.chat(SID, QUESTION, UID);

        assertEquals("生成回答", r.content());
        assertEquals(List.of("员工手册"), r.sources());
        verify(completion).complete(any(), eq(QUESTION), any(), eq("生成回答"), eq(100), anyLong());
    }

    @Test
    void streamCacheHitEmitsContentAndSources() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(hitPrep());

        List<ChatStreamEvent> events = service.chatStream(SID, QUESTION, UID).collectList().block();

        assertEquals(2, events.size());
        assertEquals(ChatStreamEvent.EventType.CONTENT, events.get(0).type());
        assertEquals("缓存答案", events.get(0).data());
        assertEquals(ChatStreamEvent.EventType.SOURCES, events.get(1).type());
        assertEquals(List.of("员工手册"), jsonListOf(events.get(1).data()));
        verify(completion).completeCached(any(), eq(QUESTION), any(), anyLong());
    }

    @Test
    void streamMissEmitsContentChunksThenSources() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(missPrep());
        givenModelAnswers("生成回答");
        when(completion.complete(any(), eq(QUESTION), any(), eq("你好"), any(), anyLong()))
                .thenReturn(List.of("员工手册"));

        List<ChatStreamEvent> events = service.chatStream(SID, QUESTION, UID).collectList().block();

        assertEquals(ChatStreamEvent.EventType.CONTENT, events.get(0).type());
        assertEquals("你", events.get(0).data());
        assertEquals(ChatStreamEvent.EventType.CONTENT, events.get(1).type());
        assertEquals(ChatStreamEvent.EventType.SOURCES, events.get(2).type());
        assertTrue(events.stream().noneMatch(e -> "[]".equals(e.data())));
        verify(guardHandle, Mockito.atLeastOnce()).close(); // doFinally 释放名额
    }

    /**
     * 流式 usage 采集：OpenAI 兼容端点(DashScope 兼容模式实测)在末尾额外发一个
     * "只带 usage、choices 为空"的分块。该分块的 result/output 为 null, 若先做输出判空
     * 就会把用量整体丢掉(表现为 chat_log.total_tokens 恒为 NULL)。
     */
    @Test
    void streamCapturesUsageFromTrailingUsageOnlyChunk() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(missPrep());
        givenModelAnswers("生成回答");
        ChatResponse usageOnly = usageOnlyResponse(1047);
        // 先构建响应对象再放进 when(), 避免在 stub 参数求值期间操作其它 mock
        ChatResponse first = responseWith("你", 0);
        ChatResponse second = responseWith("好", 50);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(first, second, usageOnly));
        when(completion.complete(any(), eq(QUESTION), any(), eq("你好"), eq(1047), anyLong()))
                .thenReturn(List.of("员工手册"));

        service.chatStream(SID, QUESTION, UID).collectList().block();

        verify(completion).complete(any(), eq(QUESTION), any(), eq("你好"), eq(1047), anyLong());
    }

    /** 仅携带 metadata.usage 的分块(choices 为空, getResult() 为 null) */
    private ChatResponse usageOnlyResponse(int totalTokens) {
        Usage usageMeta = mock(Usage.class);
        when(usageMeta.getTotalTokens()).thenReturn(totalTokens);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usageMeta);
        ChatResponse resp = mock(ChatResponse.class);
        when(resp.getResult()).thenReturn(null);
        when(resp.getMetadata()).thenReturn(metadata);
        return resp;
    }

    private List<String> jsonListOf(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 流式静默超时(线上 bug 复现): 模型长时间无增量(如思维链长思考静默)时,
     * 应在 idle 超时内主动终止并降级——不得等到上游 okhttp 60s 超时被重置,
     * 且中断提示不得写进语义缓存(否则下次同问直接命中 19 字提示)。
     */
    @Test
    void streamIdleTimeoutDegradesAndSkipsCacheWrite() {
        when(preparation.prepare(any(), eq(QUESTION))).thenReturn(missPrep());
        givenModelAnswers("生成回答");
        appProperties.getChat().setStreamIdleTimeoutMs(200);
        when(streamSpec.chatResponse()).thenReturn(Flux.never()); // 模型流永远不产增量

        long start = System.currentTimeMillis();
        List<ChatStreamEvent> events = service.chatStream(SID, QUESTION, UID).collectList().block();
        long cost = System.currentTimeMillis() - start;

        assertEquals(1, events.size());
        assertEquals(ChatStreamEvent.EventType.CONTENT, events.get(0).type());
        assertTrue(events.get(0).data().contains("终止"), "应返回可操作的降级提示: " + events.get(0).data());
        assertTrue(cost < 5_000, "应在 idle 预算内降级, 实际 " + cost + "ms");
        // 中断走失败收尾: 不写语义缓存(complete 会写缓存), 只记审计
        verify(completion).completeInterrupted(any(), eq(QUESTION), anyString(), anyLong());
        verify(completion, never()).complete(any(), eq(QUESTION), any(), anyString(), any(), anyLong());
    }

    @Test
    void concurrencyRejectionPropagatesAsBusinessException() {
        when(concurrencyGuard.acquire(any()))
                .thenThrow(new BusinessException(ErrorCode.CONCURRENT_LIMIT));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.chat(SID, QUESTION, UID));
        assertEquals(ErrorCode.CONCURRENT_LIMIT, e.getErrorCode());
    }
}
