package com.ai.system.dto;

import java.time.LocalDateTime;

/**
 * 会话原始轨迹留档条目(管理员查询)。
 *
 * @param seq       会话内单调递增序号
 * @param role      行类型 USER/ASSISTANT/SUMMARY
 * @param content   原文(SUMMARY 行为当次摘要全文)
 * @param batch     仅 SUMMARY 行: 第几次滚动摘要压缩
 * @param createdAt 入档时间
 */
public record MemoryRawEntryVO(long seq, String role, String content, Integer batch, LocalDateTime createdAt) {
}
