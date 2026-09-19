package com.ai.chat.event;

import com.ai.context.ContextComposition;
import com.ai.context.service.QueryRewriter;
import com.ai.rag.SourceVO;
import com.ai.session.entity.ChatSession;
import com.ai.rag.RagMode;

import java.util.List;

/**
 * 一次问答完成事件(同步/流式回答结束后发布)：由 {@code ChatAuditListener}
 * 异步写入 chat_log 与 context_log, 把审计落库移出对话关键路径。
 *
 * @param session     会话
 * @param userMessage 原始用户消息
 * @param answer      回答文本(流式为完整拼接结果)
 * @param sources     引用来源文档名列表
 * @param modelLabel  记录到 chat_log.model_name 的模型名
 * @param durationMs  从请求开始到回答完成的总耗时
 * @param rewrite     多轮改写结果(供 context_log 记录; 不可空)
 * @param intentMode  意图路由结果(供 context_log 记录)
 * @param composition 上下文 Token 组成快照; null=本次不写 context_log(如流式中断)
 * @param totalTokens 模型返回的 Token 用量(usage.totalTokens, 采自 ChatResponse; 可空)
 */
public record ChatCompletedEvent(ChatSession session, String userMessage, String answer,
                                 List<String> sources, String modelLabel, long durationMs,
                                 QueryRewriter.RewriteResult rewrite, RagMode intentMode,
                                 ContextComposition composition, Integer totalTokens) {
}
