package com.ai.chat.service;

import com.ai.agent.BusinessTools;
import com.ai.chat.dto.ChatResponse;
import com.ai.chat.dto.ChatStreamEvent;
import com.ai.chat.dto.RagDebugVO;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.chat.service.ChatPreparationService.PreparedChat;
import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.session.entity.ChatSession;
import com.ai.session.service.ChatSessionService;
import com.ai.session.SessionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能对话业务(需求第 3 章)——对话编排门面。
 *
 * <p>管线职责已拆分：前置(改写/缓存/路由检索/装配)在 {@link ChatPreparationService},
 * 收尾(记忆/摘要/审计/缓存写入/来源口径)在 {@link ChatCompletionService},
 * 本类只保留"输出方式"差异——同步调用与 SSE 流式编排, 以及请求链构建。
 * 意图路由关键词表见 {@link com.ai.rag.IntentRouter}。
 * 降级：模型未配置抛 {@link ErrorCode#AI_NOT_CONFIGURED}, 调用失败抛 {@link ErrorCode#AI_CALL_FAILED};
 * 向量库不可用时以空上下文继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionService sessionService;
    private final ChatPreparationService preparation;
    private final ChatCompletionService completion;
    private final ChatConcurrencyGuard concurrencyGuard;
    private final ChatClientProvider chatClientProvider;
    private final RagRetriever ragRetriever;
    private final BusinessTools businessTools;
    private final AppProperties appProperties;

    /**
     * 同步问答。
     *
     * @param sessionId   会话 ID(需存在、进行中且归属当前用户)
     * @param userMessage 用户消息
     * @param userId      当前登录用户 ID(会话归属校验)
     * @return 回答内容与引用来源(展示口径: 声明"未找到"时来源为空)
     * @throws BusinessException 会话不存在(3001)/无权操作他人会话(5002)/并发超限(6010)或模型失败
     */
    public ChatResponse chat(String sessionId, String userMessage, Long userId) {
        ChatSession session = sessionService.requireActive(sessionId, userId);
        var guardHandle = concurrencyGuard.acquire(userId);
        try {
            long start = System.currentTimeMillis();
            PreparedChat prep = preparation.prepare(session, userMessage);
            if (prep.cachedAnswer() != null) {
                completion.completeCached(session, userMessage, prep, start);
                return new ChatResponse(prep.cachedAnswer().content());
            }
            ChatClient.ChatClientRequestSpec spec =
                    buildSpec(requireChatClient(), session, prep.assembled(), false, prep.toolCalls());
            org.springframework.ai.chat.model.ChatResponse chatResponse;
            try {
                chatResponse = spec.call().chatResponse();
            } catch (Exception e) {
                // 原始异常只进日志(含堆栈), 客户端仅得友好提示, 不泄漏内部端点/网络细节
                log.error("模型调用失败: session={}", sessionId, e);
                throw new BusinessException(ErrorCode.AI_CALL_FAILED);
            }
            String answer = extractText(chatResponse);
            Integer usage = usageTotal(chatResponse);
            completion.complete(session, userMessage, prep, answer, usage, start);
            return new ChatResponse(answer);
        } finally {
            guardHandle.close();
        }
    }

    /**
     * 流式问答(SSE 类型化事件流)：仅 CONTENT(正文增量) 事件; 结束标记 data:[DONE] 由 Controller 追加。
     * 引用来源不再下发前端(只落库 chat_log.sources)。
     * 前置阶段在 boundedElastic 上执行; 并发名额在流终止(完成/出错/取消)时释放。
     *
     * @param sessionId   会话 ID(需存在、进行中且归属当前用户)
     * @param userMessage 用户消息
     * @param userId      当前登录用户 ID(会话归属校验)
     * @return 事件流(由 Controller 包装为 ServerSentEvent)
     */
    public Flux<ChatStreamEvent> chatStream(String sessionId, String userMessage, Long userId) {
        ChatSession session = sessionService.requireActive(sessionId, userId);
        var guardHandle = concurrencyGuard.acquire(userId);
        long start = System.currentTimeMillis();

        // 前置阶段：准备会话、用户消息、工具、检索结果与提示
        Mono<PreparedChat> prepare = Mono.fromCallable(() -> preparation.prepare(session, userMessage))
                .subscribeOn(Schedulers.boundedElastic());

        Flux<ChatStreamEvent> flux;
        try {
            flux = prepare.flatMapMany(prep -> {
                // 语义缓存命中: 以完整回答一次性下发(前端表现为秒回)
                if (prep.cachedAnswer() != null) {
                    completion.completeCached(session, userMessage, prep, start);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT,
                            prep.cachedAnswer().content()));
                }
                // 正常流程: 调用模型
                return streamAnswer(session, userMessage, prep, start);
            }).onErrorResume(e -> {
                log.error("流式对话失败: session={}", sessionId, e);
                String msg = "【系统提示】回答生成中断，请稍后重试。";
                completion.completeInterrupted(session, userMessage, msg, start);
                return Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT, msg));
            }).doFinally(signal -> guardHandle.close());
        } catch (RuntimeException e) {
            guardHandle.close(); // 同步构建阶段异常也要释放名额
            throw e;
        }
        return flux;
    }

    /**
     * 主回答流：正文增量作为 CONTENT 事件, 流终止时经 completion 完成收尾。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果
     * @param start       请求开始时间戳
     * @return 正文事件流(含收尾)
     */
    private Flux<ChatStreamEvent> streamAnswer(ChatSession session, String userMessage,
            PreparedChat prep, long start) {
        ChatClient.ChatClientRequestSpec spec =
                buildSpec(requireChatClient(), session, prep.assembled(), true, prep.toolCalls());
        StringBuilder collected = new StringBuilder();
        int[] usageHolder = {0};
        long idleMs = appProperties.getChat().getStreamIdleTimeoutMs();

        Flux<org.springframework.ai.chat.model.ChatResponse> upstream = spec.stream().chatResponse();
        if (idleMs > 0) {
            // 静默超时保护: 相邻增量间隔超过 idleMs(或首增量迟迟不来)抛 TimeoutException。
            // 早于上游 okhttp 60s read timeout 触发——qwen3 思考模式长推理会整段静默,
            // 实测曾卡满 60s 被重置(CANCEL), 整轮回答只剩一条中断提示。
            upstream = upstream.timeout(java.time.Duration.ofMillis(idleMs));
        }
        return upstream
                .flatMap(resp -> {
                    if (resp == null) {
                        return Flux.empty();
                    }
                    // 必须在输出判空之前取 usage: 末尾的 usage-only 分块 choices 为空(result/output 为 null),
                    // 先判空会把它丢掉——表现为流式轮次 chat_log.total_tokens 恒为 NULL。
                    Integer usage = usageTotal(resp);
                    if (usage != null) {
                        usageHolder[0] = usage;
                    }
                    if (resp.getResult() == null || resp.getResult().getOutput() == null) {
                        return Flux.empty();
                    }
                    String text = resp.getResult().getOutput().getText();
                    if (text == null || text.isEmpty()) {
                        return Flux.empty();
                    }
                    collected.append(text);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT, text));
                })
                .concatWith(Flux.defer(() ->
                        finishStream(session, userMessage, prep, collected, start, usageHolder)))
                .onErrorResume(e -> {
                    boolean idle = e instanceof java.util.concurrent.TimeoutException;
                    String tip = idle
                            ? "【系统提示】模型长时间未生成内容(可能正在深度思考)，已终止本轮，请重试或简化问题。"
                            : "【系统提示】回答生成中断，请稍后重试。";
                    log.error("流式对话中断: session={}, 静默超时={}, 已生成 {} 字, 原因: {}",
                            session.getSessionId(), idle, collected.length(), e.toString());
                    // 中断走"失败收尾": 只写记忆与审计, 不写语义缓存——
                    // 若走 complete() 会把"中断提示"当答案缓存, 下次同问直接命中这条提示
                    completion.completeInterrupted(session, userMessage, tip, start);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT, tip));
                });
    }

    /**
     * 流式收尾：经 completion 统一收尾(记忆/摘要/审计/缓存)。来源只落库, 不再下发前端。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果
     * @param collected   已收集的回答全文
     * @param start       请求开始时间戳
     * @param usageHolder 模型 Token 用量
     * @return 空流(不再发送来源事件)
     */
    private Flux<ChatStreamEvent> finishStream(ChatSession session, String userMessage,
            PreparedChat prep, StringBuilder collected, long start, int[] usageHolder) {
        long cost = System.currentTimeMillis() - start;
        String answer = collected.toString();
        completion.complete(session, userMessage, prep, answer,
                usageHolder[0] > 0 ? usageHolder[0] : null, start);
        log.info("流式对话完成: session={}, 总耗时 {}ms, 回答 {} 字",
                session.getSessionId(), cost, answer.length());
        return Flux.empty();
    }

    /**
     * RAG 检索调试：走对话同一条链路(短查询扩展 → 混合检索 → 出口判定)并回显结果，
     * 附带"这轮交给对话会判哪个出口"——调试页与对话不再有两套口径。
     *
     * @param question           查询问题
     * @param topK               Top-K(空则用配置默认)
     * @param similarityThreshold 语义路余弦阈值(空则用 {@code app.rag.similarity-threshold}, 与对话一致)
     * @param expandShort        是否允许短查询扩展
     * @return 命中来源 + 各路计数 + 出口预测
     */
    public RagDebugVO debugRetrieve(String question, Integer topK, Double similarityThreshold,
            boolean expandShort) {
        ChatPreparationService.DebugSearch debug =
                preparation.debugSearch(question, topK, similarityThreshold, expandShort);
        RetrievalOutcome outcome = debug.outcome();
        return new RagDebugVO(ragRetriever.toSources(outcome.hits()), debug.chatOutcome().name(),
                debug.retrievalQuery(), debug.expanded(), outcome.executed(), outcome.degraded(),
                debug.topK(), debug.threshold(), outcome.semanticCount(), outcome.keywordCount(),
                outcome.executed() ? outcome.semanticMaxScore() : null, outcome.hits().size(),
                appProperties.getChat().isKbOnly());
    }

    /**
     * 构建请求链(system + 历史消息 + user + 工具), 可执行 .call() 或 .stream()。
     *
     * @param client    ChatClient
     * @param session   会话
     * @param assembled 上下文装配结果
     * @param streaming 是否流式调用(仅流式请求携带 stream_options, 避免同步请求被端点拒绝)
     * @param toolCalls 本轮工具调用计数容器(经 toolContext 透传给 ToolCallLogAspect 自增,
     *                  收尾阶段据此判断是否禁止写语义缓存)
     * @return 可执行的请求规格
     */
    private ChatClient.ChatClientRequestSpec buildSpec(ChatClient client,
            ChatSession session, com.ai.context.AssembledPrompt assembled, boolean streaming,
            AtomicInteger toolCalls) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(assembled.system())
                .messages(assembled.messages())
                .user(assembled.queryForModel());
        boolean noThinking = appProperties.getChat().isDisableThinking();
        boolean includeUsage = streaming && appProperties.getChat().isStreamIncludeUsage();
        if (noThinking || includeUsage) {
            org.springframework.ai.openai.OpenAiChatOptions.Builder options =
                    org.springframework.ai.openai.OpenAiChatOptions.builder();
            if (noThinking) {
                // 主对话关闭 qwen3 思维链(配置默认关闭保质量; 提速时可打开)
                options.extraBody(Map.of("enable_thinking", false));
            }
            if (includeUsage) {
                // OpenAI 兼容端点的流式响应默认不返回 usage, 需显式索取,
                // 否则 chat_log.total_tokens 恒为空(成本审计对前端流量失效)
                options.streamUsage(true);
            }
            spec.options(options);
        }
        if (needTools(session)) {
            // 透传会话与用户到工具上下文: ToolCallLogAspect 据此回填 tool_call_log 的 session_id/user_id;
            // toolCalls 计数器同经切面自增, 收尾阶段据此判定"回答含实时业务数据"→禁止写语义缓存
            spec.tools(businessTools)
                    .toolContext(Map.of("sessionId", session.getSessionId(),
                            "userId", session.getUserId(),
                            "toolCalls", toolCalls));
        }
        return spec;
    }

    /**
     * 是否需要注册业务工具(AGENT/HYBRID 会话)。
     *
     * @param session 会话
     * @return true=注册工具
     */
    private boolean needTools(ChatSession session) {
        return session.getSessionType() == SessionType.AGENT
                || session.getSessionType() == SessionType.HYBRID;
    }

    /**
     * 获取 ChatClient；模型未配置时抛友好业务异常(降级)。
     *
     * @return ChatClient
     * @throws BusinessException AI_NOT_CONFIGURED
     */
    private ChatClient requireChatClient() {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return client;
    }

    /**
     * 提取模型回答文本(空安全)。
     *
     * @param chatResponse 模型响应(可空)
     * @return 回答文本(可为空串)
     */
    private String extractText(org.springframework.ai.chat.model.ChatResponse chatResponse) {        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        String text = chatResponse.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 从模型响应提取 Token 用量(usage.totalTokens)。
     *
     * @param chatResponse 模型响应(可空)
     * @return 总 Token 数; 不可得时 null
     */
    private Integer usageTotal(org.springframework.ai.chat.model.ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getMetadata() == null
                || chatResponse.getMetadata().getUsage() == null) {
            return null;
        }
        Integer total = chatResponse.getMetadata().getUsage().getTotalTokens();
        return total != null && total > 0 ? total : null;
    }
}
