package com.ai.session.dto;

import java.util.List;

/**
 * 会话历史消息响应(会话回显)。
 *
 * @param messages 消息列表(按时间升序)
 * @param summary  滚动摘要(记忆被摘要裁剪后的早期内容压缩, 无则 null)
 */
public record SessionMessagesVO(List<HistoryMessageVO> messages, String summary) {
}
