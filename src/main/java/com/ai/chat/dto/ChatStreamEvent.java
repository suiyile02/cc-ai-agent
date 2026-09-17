package com.ai.chat.dto;

/**
 * 流式对话事件(SSE)。类型决定前端的渲染方式：
 * <ul>
 *   <li>{@code CONTENT} —— 回答正文增量。</li>
 * </ul>
 * 结束标记为不带头部的 {@code data:[DONE]}(由 Controller 追加, 兼容既有契约)。
 * 来源不再通过 SOURCES 事件下发(2026-09 契约变更): 引用来源只落库到 {@code chat_log.sources}。
 */
public record ChatStreamEvent(EventType type, String data) {

    public enum EventType {
        CONTENT
    }
}
