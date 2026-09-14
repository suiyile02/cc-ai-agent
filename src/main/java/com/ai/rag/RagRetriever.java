package com.ai.rag;

import com.ai.chat.dto.SourceVO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识检索契约：供对话(chat)与上下文装配(context)等其它业务模块调用。
 *
 * <p>调用方只依赖本接口, 不绑定具体实现(当前为 {@link RagRetrievalService} 的
 * 语义+BM25 混合检索; 后续可替换为 Elasticsearch 等其它检索后端)。
 * 命中文档的元数据约定: doc_id/file_name/chunk_index/collection(与入库一致)。
 */
public interface RagRetriever {

    /**
     * 检索能力是否可用(存在向量库或关键词索引任一即可)。
     *
     * @return true=可检索
     *
     * <p>【未被引用】契约方法暂无调用方——可用性判断在 retrieveOutcome 内部自行完成
     * (向量库/关键词索引均不可用时返回空上下文)。
     */
    boolean available();

    /**
     * 按默认 Top-K / 阈值执行检索并返回最终命中列表。
     *
     * @param query 用户问题
     * @return 重排后的命中文档列表(可能为空)
     *
     * <p>【未被引用】单参重载无调用方——调用方均显式传 topK/threshold(见三参重载)。
     */
    List<Document> retrieve(String query);

    /**
     * 按指定 Top-K / 阈值执行检索并返回最终命中列表(检索调试接口使用)。
     *
     * @param query     用户问题
     * @param topK      召回数量上限
     * @param threshold 语义相似度阈值(低于该值不参与注入)
     * @return 重排后的命中文档列表(可能为空)
     */
    List<Document> retrieve(String query, int topK, double threshold);

    /**
     * 混合检索主流程(多路召回 + RRF + 重排), 返回各路命中明细供审计。
     *
     * @param query     用户问题
     * @param topK      最终注入 Top-K
     * @param threshold 语义路相似度阈值
     * @return 检索结果详情(命中 + 各路过计数 + 是否执行)
     */
    RetrievalOutcome retrieveOutcome(String query, int topK, double threshold);

    /**
     * 组装注入 LLM 的上下文文本(默认字符上限约束)。
     *
     * @param hits 最终命中文档(已按相关度排序)
     * @return 注入提示词的上下文串, 无命中返回空串
     *
     * <p>【未被引用】无调用方——生产装配统一走带 tokenBudget 的重载(见下)。
     */
    String buildContext(List<Document> hits);

    /**
     * 组装注入 LLM 的上下文文本(按相关度累加, 受 Token 预算与字符上限双重约束)。
     *
     * @param hits        最终命中文档(已按相关度排序)
     * @param tokenBudget RAG 段 Token 预算(≤0 表示不限 Token, 仅受字符上限约束)
     * @return 注入提示词的上下文串, 无命中返回空串
     */
    String buildContext(List<Document> hits, int tokenBudget);

    /**
     * 命中文档 → 来源 VO 列表(随响应返回并在对话日志中留存)。
     *
     * @param hits 最终命中文档
     * @return 来源列表(文件/分块/片段/分数)
     */
    List<SourceVO> toSources(List<Document> hits);
}
