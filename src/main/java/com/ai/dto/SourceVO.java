package com.ai.dto;

/**
 * RAG 引用来源。
 *
 * @param fileName    来源文件名
 * @param documentId  文档 ID(MySQL knowledge_document.id)
 * @param chunkIndex  分块序号
 * @param snippet     内容片段(检索调试/展示用)
 * @param score       相似度(向量库支持时提供)
 */
public record SourceVO(String fileName, Long documentId, Integer chunkIndex, String snippet, Double score) {
}
