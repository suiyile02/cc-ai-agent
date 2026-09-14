package com.ai.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 上下文装配结果：可直接喂给 {@code ChatClient} 的 system 文本 + 历史消息 + 当前用户输入 + 组成快照。
 *
 * @param system        组装后的 system 提示词(base + 可选 RAG 段)
 * @param messages      历史消息(可选摘要 SYSTEM 消息 + 最近对话, 时间升序)
 * @param queryForModel 发送给模型的当前用户输入(原始问题, 超预算时尾部截断)
 * @param composition   Token 组成快照(可观测/落库)
 */
public record AssembledPrompt(String system, List<Message> messages,
                              String queryForModel, ContextComposition composition) {
}
