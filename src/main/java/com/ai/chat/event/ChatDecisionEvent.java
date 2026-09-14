package com.ai.chat.event;

import com.ai.system.entity.RagDecisionLog;

/**
 * 意图路由/检索决策事件(对话请求在调用模型前发布)：由 {@code ChatAuditListener}
 * 异步写入 rag_decision_log, 即使模型调用失败也不影响决策留痕。
 *
 * @param decision 已组装好的决策日志记录
 */
public record ChatDecisionEvent(RagDecisionLog decision) {
}
