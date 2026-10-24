package com.ai.knowledge.dto;

/**
 * 文档全文查看结果（专属端点返回，不进列表 VO——MEDIUMTEXT 大字段不随分页传输）。
 *
 * @param fileName  文件名（弹窗头部展示）
 * @param fileType  文件类型
 * @param available 是否有全文：false=存量文档（full_text 列加入前入库）未生成，需重新处理
 * @param charCount 全文字符数（不可用时为 0）
 * @param content   全文原文（不可用时为 null）
 */
public record KnowledgeFullTextVO(String fileName, String fileType,
                                  boolean available, int charCount, String content) {
}
