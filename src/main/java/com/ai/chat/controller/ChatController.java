package com.ai.chat.controller;
import com.ai.chat.service.ChatService;

import com.ai.common.Result;
import com.ai.chat.dto.ChatRequest;
import com.ai.chat.dto.ChatResponse;
import com.ai.chat.dto.RagDebugRequest;
import com.ai.chat.dto.RagDebugVO;
import com.ai.user.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 智能对话接口(需求第 3 章)：同步问答 / SSE 流式问答 / RAG 检索调试。
 * Controller 只做请求映射与参数绑定，业务逻辑见 {@link ChatService}。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 同步对话(3.1)。
     *
     * @param request 请求体(sessionId + message)
     * @return 统一响应, data 含 content 与 sources
     */
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request) {
        return Result.ok(chatService.chat(request.sessionId(), request.message(),
                UserContext.requireUserId()));
    }

    /**
     * 流式对话(SSE 类型化事件流, 3.2)：
     * event=content(正文增量)/sources(引用来源文档名数组), 末尾追加 data:[DONE]。
     * 事件类型详见 {@link com.ai.chat.dto.ChatStreamEvent}。
     *
     * @param request 请求体(sessionId + message)
     * @return ServerSentEvent 事件流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody @Valid ChatRequest request) {
        return chatService.chatStream(request.sessionId(), request.message(),
                        UserContext.requireUserId())
                .map(e -> ServerSentEvent.<String>builder(e.data())
                        .event(e.type().name().toLowerCase())
                        .build())
                .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()));
    }

    /**
     * RAG 检索调试：走对话同一条链路，返回命中块(含两路原始分)与对话出口预测。
     *
     * @param request 请求体(question + 可选 topK/阈值/是否短查询扩展)
     * @return 统一响应, data 为调试结果
     */
    @PostMapping("/rag/search")
    public Result<RagDebugVO> ragSearch(@RequestBody @Valid RagDebugRequest request) {
        return Result.ok(chatService.debugRetrieve(request.question(), request.topK(),
                request.similarityThreshold(), request.expandShort()));
    }
}
