package com.ai.chat.service;
import com.ai.chat.event.ChatDecisionEvent;
import com.ai.chat.event.ChatCompletedEvent;

import com.ai.agent.BusinessTools;
import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.Strings;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.context.AssembledPrompt;
import com.ai.context.service.ContextAssembler;
import com.ai.context.ContextComposition;
import com.ai.context.ConversationMemory;
import com.ai.context.service.QueryRewriter;
import com.ai.chat.dto.ChatResponse;
import com.ai.chat.dto.ChatStreamEvent;
import com.ai.chat.dto.SourceVO;
import com.ai.session.entity.ChatSession;
import com.ai.session.entity.ChatSession.SessionType;
import com.ai.system.entity.RagDecisionLog;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;
import com.ai.rag.RagRetriever;
import com.ai.rag.RetrievalOutcome;
import com.ai.session.service.ChatSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能对话业务(需求第 3 章)。按会话类型与意图路由组织问答流程：
 * <ul>
 *   <li>RAG：意图路由 →(KB)混合检索注入 / (GENERAL)自由问答(不注册工具)</li>
 *   <li>AGENT：仅注册业务工具(不检索知识库)</li>
 *   <li>HYBRID：意图路由 + 工具两者兼备(默认)</li>
 * </ul>
 * 意图路由关键词表见 {@link IntentRouter}(app.rag.internal-keywords 可配置)。
 * 审计日志(决策/对话/上下文)经 {@link ChatDecisionEvent}/{@link ChatCompletedEvent}
 * 由 ChatAuditListener 异步落库, 不占用对话关键路径。
 * 降级：模型未配置抛 {@link ErrorCode#AI_NOT_CONFIGURED}, 调用失败抛 {@link ErrorCode#AI_CALL_FAILED};
 * 向量库不可用时以空上下文继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionService sessionService;
    private final RagRetriever ragRetriever;
    private final ChatClientProvider chatClientProvider;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final BusinessTools businessTools;
    private final AppProperties appProperties;
    private final QueryRewriter queryRewriter;
    private final ContextAssembler contextAssembler;
    private final ConversationMemory memoryService;
    private final IntentRouter intentRouter;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 一次问答的 RAG 上下文快照(供提示词组装与决策落库)。
     *
     * @param hits    最终命中文档(已重排取 Top-K)
     * @param mode    意图路由结果 KB/GENERAL
     * @param outcome 检索结果明细(执行与否/各路命中数)
     */
    private record RagContext(List<Document> hits, RagMode mode,
                              RetrievalOutcome outcome) {

        /** 未检索的空上下文(AGENT 会话 / 常识问题跳过检索时使用) */
        static RagContext empty() {
            return new RagContext(List.of(), RagMode.GENERAL,
                    RetrievalOutcome.none());
        }
    }

    /**
     * 同步问答。
     *
     * @param sessionId   会话 ID(需存在、进行中且归属当前用户)
     * @param userMessage 用户消息
     * @param userId      当前登录用户 ID(会话归属校验)
     * @return 回答内容与引用来源
     * @throws BusinessException 会话不存在(3001)/无权操作他人会话(5002)或模型未配置/调用失败(5003)
     */
    public ChatResponse chat(String sessionId, String userMessage, Long userId) {
        // 获取会话(校验归属当前用户, 防止越权)
        ChatSession session = sessionService.requireActive(sessionId, userId);
        long start = System.currentTimeMillis();

        // 多轮查询改写: 改写结果用于意图路由与检索, 原始问题用于对话与日志
        QueryRewriter.RewriteResult rw = queryRewriter.rewrite(
                sessionId, session.getSessionType(), userMessage);
        String retrievalQuery = rw.query() == null || rw.query().isBlank() ? userMessage : rw.query();

        // 意图路由/检索决策(事件异步落库审计, 即使模型不可用也会记录)
        RagContext rag = resolveRagContext(session, retrievalQuery);
        publishDecision(session, userMessage, rag, System.currentTimeMillis() - start);

        ChatClient client = requireChatClient();

        // 统一 Token 预算装配(system + 历史 + RAG + user)
        AssembledPrompt assembled = contextAssembler.assemble(
                session, userMessage, rag.hits(), rag.mode(), rw.rewritten());
        ChatClient.ChatClientRequestSpec spec = buildSpec(client, session, assembled);

        String answer;
        try {
            answer = spec.call().content();
        } catch (Exception e) {
            // 原始异常只进日志(含堆栈), 客户端仅得友好提示, 不泄漏内部端点/网络细节
            log.error("模型调用失败: session={}", sessionId, e);
            throw new BusinessException(ErrorCode.AI_CALL_FAILED);
        }
        answer = answer == null ? "" : answer;

        // 写回本轮记忆(同步完成, 保证下一轮立即可见)
        memoryService.append(sessionId, userMessage, answer);
        // 判定是否需要后台滚动摘要(异步, 不阻塞响应)
        memoryService.summarizeIfNeededAsync(sessionId);

        // 审计日志(对话/上下文)事件异步落库
        long cost = System.currentTimeMillis() - start;
        List<String> sourceNames = sourceNames(ragRetriever.toSources(rag.hits()));
        publishCompleted(session, userMessage, answer, sourceNames, cost,
                rw, rag.mode(), assembled.composition());
        return new ChatResponse(answer, sourceNames);
    }

    /**
     * 流式问答(SSE 完整事件流)。按阶段推送：
     * STAGE(理解问题→检索完成→生成回答) → CONTENT(正文增量) → SOURCES(引用来源)；
     * 结束标记 data:[DONE] 由 Controller 追加。前置阶段(改写+检索+装配)在 boundedElastic 上执行。
     *
     * @param sessionId   会话 ID(需存在、进行中且归属当前用户)
     * @param userMessage 用户消息
     * @param userId      当前登录用户 ID(会话归属校验)
     * @return 事件流(由 Controller 包装为 ServerSentEvent)
     */
    public Flux<ChatStreamEvent> chatStream(String sessionId, String userMessage, Long userId) {
        ChatSession session = sessionService.requireActive(sessionId, userId);
        long start = System.currentTimeMillis();

        Mono<PreparedChat> prepare = Mono.fromCallable(() -> prepareStage(session, userMessage, start))
                .subscribeOn(Schedulers.boundedElastic());

        return prepare.flatMapMany(prep -> streamAnswer(session, userMessage, prep, start))
                .onErrorResume(e -> {
                    log.error("流式对话失败: session={}", sessionId, e);
                    String msg = "【系统提示】回答生成中断，请稍后重试。";
                    memoryService.append(sessionId, userMessage, msg);
                    memoryService.summarizeIfNeededAsync(sessionId);
                    publishCompleted(session, userMessage, msg, List.of(),
                            System.currentTimeMillis() - start,
                            new QueryRewriter.RewriteResult(userMessage, false), RagMode.GENERAL, null);
                    return Flux.just(
                            new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT, msg),
                            sourcesEvent(List.of()));
                });
    }

    /**
     * 前置阶段(改写 + 意图路由/检索 + 装配), 在 boundedElastic 上执行。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param start       请求开始时间(耗时统计)
     * @return 前置阶段结果
     */
    private PreparedChat prepareStage(ChatSession session, String userMessage, long start) {
        QueryRewriter.RewriteResult rw = queryRewriter.rewrite(
                session.getSessionId(), session.getSessionType(), userMessage);
        String retrievalQuery = rw.query() == null || rw.query().isBlank() ? userMessage : rw.query();
        long rewriteMs = System.currentTimeMillis() - start;

        RagContext rag = resolveRagContext(session, retrievalQuery);
        publishDecision(session, userMessage, rag, System.currentTimeMillis() - start - rewriteMs);
        List<SourceVO> sources = ragRetriever.toSources(rag.hits());
        AssembledPrompt assembled = contextAssembler.assemble(
                session, userMessage, rag.hits(), rag.mode(), rw.rewritten());
        log.info("对话前置阶段完成: session={}, 改写 {}ms, 路由+检索+装配 {}ms, 命中 {} 段",
                session.getSessionId(), rewriteMs,
                System.currentTimeMillis() - start - rewriteMs, rag.hits().size());
        return new PreparedChat(rw, rag, sources, assembled);
    }

    /**
     * 主回答流：正文增量作为 CONTENT 事件; 结束时写回记忆/触发摘要/发审计事件并追加 SOURCES。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果
     * @param start       请求开始时间
     * @return 正文事件流(含收尾)
     */
    private Flux<ChatStreamEvent> streamAnswer(ChatSession session, String userMessage,
            PreparedChat prep, long start) {
        ChatClient.ChatClientRequestSpec spec =
                buildSpec(requireChatClient(), session, prep.assembled());
        StringBuilder collected = new StringBuilder();

        return spec.stream().content()
                .flatMap(text -> {
                    if (text == null || text.isEmpty()) {
                        return Flux.empty();
                    }
                    collected.append(text);
                    return Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT, text));
                })
                .concatWith(Flux.defer(() ->
                        finishStream(session, userMessage, prep, collected, start)))
                .onErrorResume(e -> {
                    log.error("流式对话中断: session={}", session.getSessionId(), e);
                    collected.append("【系统提示】回答生成中断，请稍后重试。");
                    return Flux.concat(
                            Flux.just(new ChatStreamEvent(ChatStreamEvent.EventType.CONTENT,
                                    "【系统提示】回答生成中断，请稍后重试。")),
                            finishStream(session, userMessage, prep, collected, start));
                });
    }

    /**
     * 流式收尾：写回记忆/触发异步摘要/发布完成审计事件, 并追加 SOURCES 事件。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @param prep        前置阶段结果
     * @param collected   已收集的回答全文
     * @param start       请求开始时间
     * @return SOURCES 事件
     */
    private Flux<ChatStreamEvent> finishStream(ChatSession session, String userMessage,
            PreparedChat prep, StringBuilder collected, long start) {
        long cost = System.currentTimeMillis() - start;
        String answer = collected.toString();
        memoryService.append(session.getSessionId(), userMessage, answer);
        memoryService.summarizeIfNeededAsync(session.getSessionId());
        List<String> sourceNames = sourceNames(prep.sources());
        publishCompleted(session, userMessage, answer, sourceNames, cost,
                prep.rw(), prep.rag().mode(), prep.assembled().composition());
        log.info("流式对话完成: session={}, 总耗时 {}ms, 回答 {} 字, 来源 {}",
                session.getSessionId(), cost, answer.length(), sourceNames);
        return Flux.just(sourcesEvent(sourceNames));
    }

    /** 流式前置阶段(改写+检索+装配)的结果载体 */
    private record PreparedChat(QueryRewriter.RewriteResult rw, RagContext rag,
                                List<SourceVO> sources, AssembledPrompt assembled) {

        /** RETRIEVED 阶段提示文案(含命中数/跳过原因) */
        String hitMessage() {
            if (rag.mode() != RagMode.KB) {
                return "无需检索知识库，直接回答";
            }
            return rag.hits().isEmpty()
                    ? "知识库中未检索到相关资料"
                    : "已检索到 " + rag.hits().size() + " 段相关资料";
        }
    }

    /** 构造 STAGE 事件(data 为 {stage,message} JSON) */
    private ChatStreamEvent stageEvent(String stage, String message) {
        try {
            return new ChatStreamEvent(ChatStreamEvent.EventType.STAGE,
                    objectMapper.writeValueAsString(Map.of("stage", stage, "message", message)));
        } catch (Exception e) {
            return new ChatStreamEvent(ChatStreamEvent.EventType.STAGE,
                    "{\"stage\":\"" + stage + "\"}");
        }
    }

    /**
     * 从命中文档提取来源文档名(去重并保持相关度顺序)——前端展示只需文档名。
     *
     * @param sources 命中来源列表
     * @return 文档名列表
     */
    private List<String> sourceNames(List<SourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .map(SourceVO::fileName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
    }

    /** 构造 SOURCES 事件(data 为来源文档名 JSON 数组) */
    private ChatStreamEvent sourcesEvent(List<String> sources) {
        try {
            return new ChatStreamEvent(ChatStreamEvent.EventType.SOURCES,
                    objectMapper.writeValueAsString(sources));
        } catch (Exception e) {
            return new ChatStreamEvent(ChatStreamEvent.EventType.SOURCES, "[]");
        }
    }

    /**
     * RAG 检索调试：直接返回命中的知识块(忽略意图路由, 用于调优 Top-K/阈值)。
     *
     * @param question           查询问题
     * @param topK               Top-K(空则用配置默认)
     * @param similarityThreshold 相似度阈值(空则用配置默认)
     * @return 命中来源列表
     */
    public List<SourceVO> debugRetrieve(String question, Integer topK, Double similarityThreshold) {
        List<Document> hits = ragRetriever.retrieve(question,
                topK == null ? appProperties.getRag().getTopK() : topK,
                similarityThreshold == null ? appProperties.getRag().getSimilarityThreshold()
                        : similarityThreshold);
        return ragRetriever.toSources(hits);
    }

    /**
     * 意图路由 + RAG 检索：
     * AGENT 会话不检索；RAG/HYBRID 会话在 autoRoute=true 时先按问题内容路由——
     * 常识/闲聊(mode=GENERAL)跳过检索, 知识库类问题(mode=KB)才执行混合检索。
     *
     * @param session     会话
     * @param userMessage 用户消息
     * @return RAG 上下文快照
     */
    private RagContext resolveRagContext(ChatSession session, String userMessage) {
        boolean ragSession = session.getSessionType() == SessionType.RAG
                || session.getSessionType() == SessionType.HYBRID;
        if (!ragSession) {
            return RagContext.empty();
        }
        if (appProperties.getRag().isAutoRoute()
                && intentRouter.route(userMessage) == RagMode.GENERAL) {
            log.debug("RAG 意图路由：通用/常识问题, 跳过检索 sessionId={}", session.getSessionId());
            return RagContext.empty();
        }
        log.debug("RAG 意图路由：知识库类问题, 执行检索 sessionId={}", session.getSessionId());
        RetrievalOutcome outcome = ragRetriever.retrieveOutcome(
                userMessage, appProperties.getRag().getTopK(),
                appProperties.getRag().getSimilarityThreshold());
        return new RagContext(outcome.hits(), RagMode.KB, outcome);
    }

    /**
     * 构建请求链(system + 历史消息 + user + 工具), 可执行 .call() 或 .stream()。
     *
     * @param client    ChatClient
     * @param session   会话
     * @param assembled 上下文装配结果
     * @return 可执行的请求规格
     */
    private ChatClient.ChatClientRequestSpec buildSpec(ChatClient client,
            ChatSession session, AssembledPrompt assembled) {
        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(assembled.system())
                .messages(assembled.messages())
                .user(assembled.queryForModel());
        if (appProperties.getChat().isDisableThinking()) {
            // 主对话关闭 qwen3 思维链(配置默认关闭保质量; 提速时可打开)
            spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false)));
        }
        if (needTools(session)) {
            // 透传会话与用户到工具上下文: ToolCallLogAspect 据此回填 tool_call_log 的 session_id/user_id
            spec.tools(businessTools)
                    .toolContext(Map.of("sessionId", session.getSessionId(),
                            "userId", session.getUserId()));
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
     * 发布“意图路由/检索决策”事件(异步写 rag_decision_log, 模型失败也留痕)。
     *
     * @param session     会话
     * @param userMessage 用户消息(截断 500 保存)
     * @param rag         RAG 上下文快照(含路由模式与各路命中数)
     * @param costMs      决策与检索耗时
     */
    private void publishDecision(ChatSession session, String userMessage, RagContext rag, long costMs) {
        var outcome = rag.outcome() == null
                ? RetrievalOutcome.none() : rag.outcome();
        RagDecisionLog entry = new RagDecisionLog();
        entry.setSessionId(session.getSessionId());
        entry.setUserId(session.getUserId());
        entry.setUserMessage(Strings.truncate(userMessage, 500));
        entry.setRagMode(rag.mode().name());
        entry.setSessionType(session.getSessionType().name());
        entry.setRetrievalExecuted(rag.mode() == RagMode.KB && outcome.executed());
        entry.setSemanticHits(outcome.semanticCount());
        entry.setKeywordHits(outcome.keywordCount());
        entry.setFinalHits(rag.hits().size());
        entry.setTopK(appProperties.getRag().getTopK());
        entry.setSimilarityThreshold(appProperties.getRag().getSimilarityThreshold());
        entry.setRerankMode(appProperties.getRag().getRerankMode());
        entry.setDurationMs((int) costMs);
        eventPublisher.publishEvent(new ChatDecisionEvent(entry));
    }

    /**
     * 发布“问答完成”事件(异步写 chat_log 与 context_log)。
     *
     * @param session     会话
     * @param userMessage 原始用户消息
     * @param answer      回答文本
     * @param sources     引用来源
     * @param durationMs  总耗时
     * @param rw          查询改写结果
     * @param mode        意图路由结果
     * @param composition 上下文组成快照(null=不写 context_log)
     */
    private void publishCompleted(ChatSession session, String userMessage, String answer,
            List<String> sources, long durationMs, QueryRewriter.RewriteResult rw,
            RagMode mode, ContextComposition composition) {
        eventPublisher.publishEvent(new ChatCompletedEvent(session, userMessage, answer,
                sources, resolveModelLabel(), durationMs, rw, mode, composition));
    }

    /**
     * 解析对话日志用的模型名：优先取 ChatModel 默认选项中的实际模型,
     * 不可得时回退 app.chat.model-label 配置, 避免 chat_log.model_name 与实际模型漂移。
     *
     * @return 模型名标签
     */
    private String resolveModelLabel() {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null && chatModel.getDefaultOptions() != null
                && chatModel.getDefaultOptions().getModel() != null
                && !chatModel.getDefaultOptions().getModel().isBlank()) {
            return chatModel.getDefaultOptions().getModel();
        }
        return appProperties.getChat().getModelLabel();
    }

}
