package com.ai.chat.dto;

/**
 * 流式对话事件(SSE)。类型决定前端的渲染方式：
 * <ul>
 *   <li>{@code STAGE} —— 业务阶段提示(理解问题/检索/生成), data 为 JSON（stage+message）；</li>
 *   <li>{@code CONTENT} —— 回答正文增量；</li>
 *   <li>{@code SOURCES} —— 引用来源 JSON 数组(正文结束后、[DONE] 之前)。</li>
 * </ul>
 * 结束标记仍为不带头部的 {@code data:[DONE]}(由 Controller 追加, 兼容既有契约)。
 */
public record ChatStreamEvent(EventType type, String data) {

    public enum EventType {
        STAGE, CONTENT, SOURCES
    }
}
