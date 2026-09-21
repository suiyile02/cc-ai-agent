package com.ai.rag;

/**
 * RAG 引用来源(检索能力的输出契约), 由 {@link RagRetriever#toSources} 产出。
 * 归属 rag 而非 chat/dto：生产方定义契约, 避免下层模块反向依赖上层编排模块。
 *
 * <p><b>三个分数不是同一口径</b>：{@code score} 是重排后的融合分(相对名次, 单路榜首恒为 1.0,
 * 用于排序不用于判断相关性)；{@code semanticScore} 才是出口判据使用的**原始余弦相似度**；
 * {@code keywordScore} 是 BM25 原始分。只有关键词命中的块 {@code semanticScore} 为 null,
 * 意味着对话侧按"无知识库依据"处理。
 *
 * @param fileName      来源文件名
 * @param documentId    文档 ID(knowledge_document.id)
 * @param chunkIndex    分块序号
 * @param snippet       内容片段(检索调试用, ≤300 字符)
 * @param score         融合/重排分(排序用, 非相似度)
 * @param semanticScore 语义路原始余弦分; 该块无向量路证据时 null
 * @param keywordScore  关键词路 BM25 原始分; 该块无词面命中时 null
 */
public record SourceVO(String fileName, Long documentId, Integer chunkIndex, String snippet,
                       Double score, Double semanticScore, Double keywordScore) {
}
