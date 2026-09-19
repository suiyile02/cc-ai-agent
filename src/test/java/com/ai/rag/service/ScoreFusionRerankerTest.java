package com.ai.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScoreFusionReranker} 单元测试：两路齐全按 6:4 加权、单路直取原分、按融合分降序。
 */
class ScoreFusionRerankerTest {

    private final ScoreFusionReranker reranker = new ScoreFusionReranker();

    private RetrievalCandidate candidate(long docId, Double sem, Double kw) {
        Document doc = Document.builder().id("c" + docId).text("t")
                .metadata(Map.of("doc_id", docId, "chunk_index", 0)).build();
        return new RetrievalCandidate(doc, sem, kw, sem == null ? null : 1, kw == null ? null : 1);
    }

    @Test
    void fusesBothPathScoresWithWeights() {
        // BM25 按本批最大值归一: doc1 的 kw 即最大值 → 0.6*1.0 + 0.4*1.0
        List<RetrievalCandidate> ordered = reranker.rerank("q", List.of(
                candidate(1L, 1.0, 10.0), candidate(2L, 0.5, 5.0)));

        assertEquals(1L, DocumentMeta.docId(ordered.get(0).doc()));
        assertEquals(1.0, ordered.get(0).fusedScore(), 1e-9);
        assertEquals(0.6 * 0.5 + 0.4 * 0.5, ordered.get(1).fusedScore(), 1e-9);
    }

    @Test
    void singlePathCandidateKeepsItsOwnScore() {
        List<RetrievalCandidate> ordered = reranker.rerank("q", List.of(
                candidate(1L, 0.42, null), candidate(2L, null, 3.0)));

        assertEquals(0.42, ordered.stream().filter(c -> c.sem() != null)
                .findFirst().orElseThrow().fusedScore(), 1e-9, "只有语义命中时不掺权重");
        assertEquals(1.0, ordered.stream().filter(c -> c.kw() != null)
                .findFirst().orElseThrow().fusedScore(), 1e-9, "只有关键词命中时归一化到 1");
    }

    @Test
    void sortedByFusedScoreDescending() {
        List<RetrievalCandidate> ordered = reranker.rerank("q", List.of(
                candidate(1L, 0.1, null), candidate(2L, 0.9, null), candidate(3L, 0.5, null)));

        assertEquals(List.of(2L, 3L, 1L),
                ordered.stream().map(c -> DocumentMeta.docId(c.doc())).toList());
        assertTrue(ordered.get(0).fusedScore() >= ordered.get(2).fusedScore());
    }
}
