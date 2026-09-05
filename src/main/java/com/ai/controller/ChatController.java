package com.ai.controller;

import com.ai.common.Result;
import com.ai.dto.ChatRequest;
import com.ai.dto.ChatResponse;
import com.ai.dto.RagDebugRequest;
import com.ai.dto.SourceVO;
import com.ai.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 智能对话(需求第 3 章)：同步问答 / SSE 流式问答 / RAG 检索调试。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 3.1 同步对话：POST /api/ai/chat */
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody @Valid ChatRequest request) {
        return Result.ok(chatService.chat(request.sessionId(), request.message()));
    }

    /**
     * 3.2 流式对话(SSE)：POST /api/ai/chat/stream
     * 消息格式：data:{"content":"..."} ... data:[DONE]
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody @Valid ChatRequest request) {
        return chatService.chatStream(request.sessionId(), request.message())
                .map(chunk -> ServerSentEvent.<String>builder(chunk).build())
                .concatWith(Flux.just(ServerSentEvent.builder("[DONE]").build()));
    }

    /** RAG 检索调试(直接看命中的知识块与相似度)：POST /api/ai/rag/search */
    @PostMapping("/rag/search")
    public Result<List<SourceVO>> ragSearch(@RequestBody @Valid RagDebugRequest request) {
        List<SourceVO> hits = chatService.debugRetrieve(request.question(),
                request.topK(), request.similarityThreshold());
        return Result.ok(hits);
    }
}
