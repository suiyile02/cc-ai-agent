package com.ai.session.dto;

/**
 * 历史消息回显项(仅 USER/ASSISTANT 纯文本)。
 *
 * @param role    角色 user/assistant
 * @param content 消息文本
 */
public record HistoryMessageVO(String role, String content) {
}
