package com.ai.rag;

/**
 * RAG 引用来源(检索能力的输出契约), 由 {@link RagRetriever#toSources} 产出。
 * 归属 rag 而非 chat/dto：生产方定义契约, 避免下层模块反向依赖上层编排模块。
 *
 * @param fileName   来源文件名
 * @param documentId 文档 ID(knowledge_document.id)
 * @param chunkIndex 分块序号
 * @param snippet    内容片段(检索调试用, ≤300 字符)
 * @param score      重排后相似度(向量库/融合分数可得时提供, 否则 null)
 */
public record SourceVO(String fileName, Long documentId, Integer chunkIndex, String snippet, Double score) {
}
