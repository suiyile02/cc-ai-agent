package com.ai.common;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

/**
 * 消息渲染工具：把 Spring AI 消息列表渲染为「角色: 文本」的多行对话稿，
 * 供查询改写/历史摘要等提示词组装复用。
 */
public final class MessageTextRenderer {

    private MessageTextRenderer() {
    }

    /**
     * 渲染消息列表为多行对话稿。
     *
     * @param messages 消息列表(可空元素自动跳过)
     * @return 「角色: 文本」逐行拼接的对话稿
     */
    public static String render(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m == null) {
                continue;
            }
            sb.append(roleLabel(m.getMessageType())).append(": ")
                    .append(m.getText() == null ? "" : m.getText()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 消息类型 → 中文角色标签。
     *
     * @param type 消息类型
     * @return 角色标签
     */
    private static String roleLabel(MessageType type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case USER -> "用户";
            case ASSISTANT -> "助手";
            case SYSTEM -> "系统";
            default -> type.name();
        };
    }
}
