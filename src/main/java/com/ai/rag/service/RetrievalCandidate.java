package com.ai.rag.service;

import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.Map;

/**
 * 融合期的候选分块：同一分块在语义路与关键词路各自的分数与排名，以及重排后的最终分数。
 *
 * <p>不可变（record）——融合/重排各阶段以"生成新候选"的方式传递结果，避免流水线中途改写影响其它分支。
 *
 * @param doc       命中分块
 * @param sem       语义相似度(0..1)，该路未命中为 null
 * @param kw        关键词 BM25 原始分，该路未命中为 null
 * @param semRank   语义路名次(1 起)，未命中为 null
 * @param kwRank    关键词路名次(1 起)，未命中为 null
 * @param fusedScore 重排后分数；未重排为 {@link Double#NaN}
 */
record RetrievalCandidate(Document doc, Double sem, Double kw,
                          Integer semRank, Integer kwRank, double fusedScore) {

    RetrievalCandidate(Document doc, Double sem, Double kw, Integer semRank, Integer kwRank) {
        this(doc, sem, kw, semRank, kwRank, Double.NaN);
    }

    /** 带融合分数的新候选（原候选不可变） */
    RetrievalCandidate withFused(double fused) {
        return new RetrievalCandidate(doc, sem, kw, semRank, kwRank, fused);
    }

    /** 补上关键词路分数与名次后的新候选（两路合并时用；分数缺失保持 null 不参与融合） */
    RetrievalCandidate withKeyword(Double keywordScore, int rank) {
        return new RetrievalCandidate(doc, sem, keywordScore, semRank, rank, fusedScore);
    }

    /**
     * 候选 → 注入用 Document：把重排分数挂到 {@code score} 与 {@code metadata.rerank_score}，
     * 同时把两路**原始分**留进 metadata——融合分是相对名次(单路榜首恒为 1.0)，只剩它就无法判断
     * "这条到底是真相似还是仅词面命中"，检索调试与出口判据都需要能看到原始余弦分。
     *
     * @return 交给上下文渲染器的最终文档
     */
    Document toDocument() {
        Map<String, Object> md = new HashMap<>(doc.getMetadata());
        md.put("rerank_score", fusedScore);
        if (sem != null) {
            md.put("semantic_score", sem);
        }
        if (kw != null) {
            md.put("keyword_score", kw);
        }
        return Document.builder()
                .id(doc.getId())
                .text(doc.getText())
                .metadata(md)
                .score(fusedScore)
                .build();
    }
}
