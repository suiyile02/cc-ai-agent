package com.ai.rag.service;

import com.ai.common.Strings;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 命中分块的元数据读取口径（向量路与关键词路共用）。
 *
 * <p>键名与入库时写入的 payload 一一对应（{@code doc_id}/{@code file_name}/{@code chunk_index}），
 * 是"删除按文档精确清理""两路召回按分块去重"的依据，改动必须与
 * {@code DocumentIngestionService.addMetadata} 同步。
 */
final class DocumentMeta {

    /** 关键词命中转 Document 时的 id 前缀（与向量点 id 空间隔离） */
    private static final String KEYWORD_ID_PREFIX = "kw-";
    /** 来源展示片段长度上限 */
    private static final int SNIPPET_MAX_CHARS = 300;

    private DocumentMeta() {
    }

    /**
     * 语义相似度(0..1)：优先 {@code Document.score}，否则由 {@code metadata.distance} 换算。
     *
     * @param doc 命中分块
     * @return 相似度；两路来源都不可得时返回 null
     */
    static Double similarity(Document doc) {
        Double score = doc.getScore();
        if (score != null) {
            return score;
        }
        if (doc.getMetadata().get("distance") instanceof Number d) {
            return clamp01(1.0 - d.doubleValue());
        }
        return null;
    }

    /**
     * 语义路的**原始余弦分**（与出口判据同口径），与融合分区分开。
     *
     * <p>{@link #similarity} 返回的是"当前挂在 score 上的分"，重排后那是融合分（相对名次，
     * 单路榜首恒为 1.0），不能当相似度读。本方法优先取 {@code metadata.semantic_score}；
     * 纯语义路直出（未走融合，无 {@code rerank_score}）时 score 就是原始余弦分；
     * 仅关键词命中则该值不可得，返回 null（对话侧据此判"无据"）。
     *
     * @param doc 命中分块
     * @return 原始余弦相似度；该分块没有语义路证据时 null
     */
    static Double semanticScore(Document doc) {
        if (doc.getMetadata().get("semantic_score") instanceof Number n) {
            return n.doubleValue();
        }
        return doc.getMetadata().containsKey("rerank_score") ? null : doc.getScore();
    }

    /**
     * 关键词路的 BM25 原始得分。
     *
     * @param doc 命中分块
     * @return 得分；非关键词路时 null
     */
    static Double keywordScore(Document doc) {
        return doc.getMetadata().get("keyword_score") instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * 两路召回的去重主键：{@code doc_id:chunk_index}。
     *
     * @param doc 命中分块
     * @return 去重键；元数据缺失时退回文档 id（再缺失用身份哈希，保证不相撞）
     */
    static String chunkKey(Document doc) {
        Map<String, Object> md = doc.getMetadata();
        if (md.get("doc_id") instanceof Number d && md.get("chunk_index") instanceof Number c) {
            return d.longValue() + ":" + c.intValue();
        }
        return doc.getId() == null ? "unknown:" + System.identityHashCode(doc) : doc.getId();
    }

    /** 来源文件名（缺失时为占位文案，避免展示空白） */
    static String fileName(Document doc) {
        Object v = doc.getMetadata().get("file_name");
        return v == null ? "未知来源" : String.valueOf(v);
    }

    /** 文档 ID（knowledge_document.id），缺失 null */
    static Long docId(Document doc) {
        return doc.getMetadata().get("doc_id") instanceof Number n ? n.longValue() : null;
    }

    /** 分块序号，缺失 null */
    static Integer chunkIndex(Document doc) {
        return doc.getMetadata().get("chunk_index") instanceof Number n ? n.intValue() : null;
    }

    /** 展示用片段（≤300 字符，按码点安全截断并加省略号） */
    static String snippet(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        String cut = Strings.truncate(text, SNIPPET_MAX_CHARS);
        return cut.length() == text.length() ? cut : cut + "...";
    }

    /**
     * 关键词命中 → 与语义命中同构的 Document（供两路合并与后续来源展示）。
     *
     * @param hit        关键词索引命中
     * @param collection 当前向量集合名（保持与语义路元数据一致）
     * @return 合成 Document（id 带 {@code kw-} 前缀，metadata 含 keyword_score）
     */
    static Document fromKeywordHit(KeywordIndex.Hit hit, String collection) {
        Map<String, Object> md = new java.util.HashMap<>();
        md.put("doc_id", hit.docId());
        md.put("file_name", hit.fileName());
        md.put("chunk_index", hit.chunkIndex());
        md.put("collection", collection);
        md.put("keyword_score", hit.score());
        return Document.builder()
                .id(KEYWORD_ID_PREFIX + hit.key())
                .text(hit.text())
                .metadata(md)
                .build();
    }

    /** 收敛到 0..1（distance 可能因近似度量越界） */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
