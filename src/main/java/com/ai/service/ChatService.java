package com.ai.service;

import com.ai.agent.BusinessTools;
import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.dto.ChatResponse;
import com.ai.dto.SourceVO;
import com.ai.entity.ChatSession;
import com.ai.entity.ChatSession.SessionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 智能对话(需求第 3 章)。按会话类型路由：
 * <ul>
 *   <li>RAG：知识库检索 + 上下文注入(不注册工具)</li>
 *   <li>AGENT：仅注册业务工具(不注入知识上下文)</li>
 *   <li>HYBRID：两者兼备</li>
 * </ul>
 * 记忆：MessageChatMemoryAdvisor 按 conversationId=sessionId 读写。
 * 降级：模型/向量库不可用时抛出 {@link ErrorCode#AI_NOT_CONFIGURED} 或空上下文继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionService sessionService;
    private final ChatLogService chatLogService;
    private final RagRetrievalService ragRetrievalService;
    private final PromptService promptService;
    private final ObjectProvider<ChatClient> chatClientProvider;
    private final BusinessTools businessTools;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private record RagContext(List<Document> hits, String contextText) {
        static RagContext empty() {
            return new RagContext(List.of(), "");
        }
    }

    /** 同步问答 */
    public ChatResponse chat(String sessionId, String userMessage) {
        ChatSession session = sessionService.requireActive(sessionId);
        long start = System.currentTimeMillis();
        RagContext rag = resolveRagContext(session, userMessage);
        ChatClient client = requireChatClient();

        String answer;
        try {
            answer = buildSpec(client, session, rag, userMessage).call().content();
        } catch (Exception e) {
            log.error("模型调用失败: session={}", sessionId, e);
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED,
                    "模型调用失败：" + shortMessage(e));
        }
        answer = answer == null ? "" : answer;

        List<SourceVO> sources = ragRetrievalService.toSources(rag.hits);
        recordLog(session, userMessage, answer, sources, null,
                System.currentTimeMillis() - start);
        return new ChatResponse(answer, sources);
    }

    /** 流式问答(SSE)：Controller 包装为 ServerSentEvent 序列 */
    public Flux<String> chatStream(String sessionId, String userMessage) {
        ChatSession session = sessionService.requireActive(sessionId);
        RagContext rag = resolveRagContext(session, userMessage);
        ChatClient client = requireChatClient();
        List<SourceVO> sources = ragRetrievalService.toSources(rag.hits);
        long start = System.currentTimeMillis();

        StringBuilder collected = new StringBuilder();
        return buildSpec(client, session, rag, userMessage).stream().content()
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        collected.append(chunk);
                    }
                })
                .doOnComplete(() -> {
                    recordLog(session, userMessage, collected.toString(), sources,
                            null, System.currentTimeMillis() - start);
                })
                .onErrorResume(e -> {
                    log.error("流式对话中断: session={}", sessionId, e);
                    String msg = "【系统提示】回答生成中断，请稍后重试。";
                    collected.append(msg);
                    recordLog(session, userMessage, collected.toString(), sources,
                            null, System.currentTimeMillis() - start);
                    return Flux.just(msg);
                });
    }

    /** RAG 检索调试(直接返回命中结果, 需求 3.3 检索参数调优用) */
    public List<SourceVO> debugRetrieve(String question, Integer topK, Double threshold) {
        List<Document> hits = ragRetrievalService.retrieve(question,
                topK == null ? appProperties.getRag().getTopK() : topK,
                threshold == null ? appProperties.getRag().getSimilarityThreshold() : threshold);
        return ragRetrievalService.toSources(hits);
    }

    // ------------------------------------------------------------------

    private RagContext resolveRagContext(ChatSession session, String userMessage) {
        boolean useRag = session.getSessionType() == SessionType.RAG
                || session.getSessionType() == SessionType.HYBRID;
        if (!useRag) {
            return RagContext.empty();
        }
        List<Document> hits = ragRetrievalService.retrieve(userMessage);
        return new RagContext(hits, ragRetrievalService.buildContext(hits));
    }

    private ChatClient.ChatClientRequestSpec buildSpec(ChatClient client,
            ChatSession session, RagContext rag, String userMessage) {
        boolean withTools = session.getSessionType() == SessionType.AGENT
                || session.getSessionType() == SessionType.HYBRID;
        String system = promptService.systemFor(session.getSessionType(),
                !rag.hits.isEmpty(), rag.contextText);

        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(system)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, session.getSessionId()));
        if (withTools) {
            spec.tools(businessTools);
        }
        return spec;
    }

    private ChatClient requireChatClient() {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return client;
    }

    private void recordLog(ChatSession session, String userMessage, String answer,
            List<SourceVO> sources, String toolCallsJson, long durationMs) {
        try {
            String sourcesJson = objectMapper.writeValueAsString(sources);
            chatLogService.record(session, userMessage, answer, sourcesJson,
                    toolCallsJson, appProperties.getChat().getModelLabel(),
                    (int) durationMs);
        } catch (Exception e) {
            log.warn("写入对话日志失败(忽略): {}", e.getMessage());
        }
    }

    private String shortMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return e.getClass().getSimpleName();
        }
        return msg.length() <= 200 ? msg : msg.substring(0, 200);
    }
}
