package com.ai.session;

/**
 * 会话类型：决定该会话允许使用哪些能力（检索 / 工具）。以枚举 name 持久化到 `chat_session.session_type`。
 *
 * <p>归属 session 模块根包而非嵌在实体里：它是被 prompt/context/chat 共同引用的领域概念,
 * 内嵌在 ORM 实体中会迫使每个使用者连带依赖实体。
 */
public enum SessionType {
    /** 仅知识库问答, 不挂工具 */
    RAG,
    /** 仅 Agent 工具, 不检索知识库 */
    AGENT,
    /** 检索 + 工具（默认） */
    HYBRID
}
