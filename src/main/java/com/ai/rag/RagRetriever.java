package com.ai.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识检索契约：供对话(chat)与上下文装配(context)等模块调用。
 *
 * <p>调用方只依赖本接口，不绑定实现（当前为 {@link com.ai.rag.service.RagRetrievalService}
 * 的语义+BM25 混合检索，可替换为其它检索后端）。命中文档的元数据约定与入库一致：
 * {@code doc_id}/{@code file_name}/{@code chunk_index}/{@code collection}。
 */
public interface RagRetriever {

    /**
     * 按指定 Top-K / 阈值执行检索并返回最终命中列表（检索调试接口使用）。
     *
     * @param query     用户问题
     * @param topK      召回数量上限
     * @param threshold 语义相似度阈值（低于该值不参与注入）
     * @return 重排后的命中文档列表（可能为空）
     */
    List<Document> retrieve(String query, int topK, double threshold);

    /**
     * 混合检索主流程（多路召回 + RRF + 重排），返回各路命中明细供审计。
     *
     * <p>实现方必须在超时/故障时降级为**已执行、零命中**（而非"未执行"），
     * 以保住 {@code rag_mode=KB 且 retrieval_executed=false} 只对应语义缓存命中的审计不变式。
     *
     * @param query     用户问题
     * @param topK      最终注入 Top-K
     * @param threshold 语义路相似度阈值
     * @return 检索结果详情（命中 + 各路过计数 + 是否执行/是否降级）
     */
    RetrievalOutcome retrieveOutcome(String query, int topK, double threshold);

    /**
     * 组装注入 LLM 的上下文文本（按相关度累加，受 Token 预算与字符上限双重约束）。
     *
     * @param hits        最终命中文档（已按相关度排序）
     * @param tokenBudget RAG 段 Token 预算（≤0 表示不限 Token，仅受字符上限约束）
     * @return 注入提示词的上下文串；无命中返回空串
     */
    String buildContext(List<Document> hits, int tokenBudget);

    /**
     * 命中文档 → 来源列表（落 {@code chat_log.sources}，供审计回看）。
     *
     * @param hits 最终命中文档
     * @return 来源列表（文件/分块/片段/分数）
     */
    List<SourceVO> toSources(List<Document> hits);
}
