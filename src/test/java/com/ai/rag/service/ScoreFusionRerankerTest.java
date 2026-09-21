package com.ai.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * 融合分写进 {@code score} 后，两路**原始分**必须仍可从 metadata 取回——
     * 检索调试页与出口判据都要看真实余弦分，而融合分是相对名次(词面榜首恒为 1.0)。
     */
    @Test
    void rawPathScoresSurviveIntoFinalDocument() {
        List<RetrievalCandidate> ordered = reranker.rerank("q", List.of(
                candidate(1L, 0.4097, 12.0), candidate(2L, null, 12.0)));
        // 注意别按位次取：本批里"仅词面命中"的归一分(1.0)会盖过双路命中(0.6*0.4097+0.4)，
        // 这本身就是"融合分不能当相似度"的例证。按 doc_id 取。
        Document bothPaths = docOf(ordered, 1L);
        Document keywordOnly = docOf(ordered, 2L);

        assertEquals(0.4097, DocumentMeta.semanticScore(bothPaths), 1e-9, "原始余弦分应可取回");
        assertEquals(12.0, DocumentMeta.keywordScore(bothPaths), 1e-9);
        assertEquals(0.6 * 0.4097 + 0.4, DocumentMeta.similarity(bothPaths), 1e-9,
                "similarity() 返回的是融合分, 不能当相似度读");
        assertNull(DocumentMeta.semanticScore(keywordOnly),
                "仅词面命中=无语义证据, 出口判据据此判无据");
        assertEquals(1.0, DocumentMeta.similarity(keywordOnly), 1e-9,
                "BM25 榜首归一后恒为 1.0——这正是调试页分数看着偏高的来源");
    }

    private Document docOf(List<RetrievalCandidate> candidates, long docId) {
        return candidates.stream()
                .filter(c -> Long.valueOf(docId).equals(DocumentMeta.docId(c.doc())))
                .findFirst().orElseThrow().toDocument();
    }

    /** 未走融合(关键词路为空, 语义路直出)时 score 本身就是原始余弦分 */
    @Test
    void semanticOnlyPathWithoutFusionStillReportsRawCosine() {
        Document direct = Document.builder().id("c9").text("t")
                .metadata(Map.of("doc_id", 9L, "chunk_index", 0)).score(0.55).build();

        assertEquals(0.55, DocumentMeta.semanticScore(direct), 1e-9);
    }
}
