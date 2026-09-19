package com.ai.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RrfFuser} 单元测试：按分块去重、双路名次合并、RRF 排序与并列稳定序。
 */
class RrfFuserTest {

    private Document chunk(long docId, int chunkIndex, Double score, Double bm25) {
        Map<String, Object> md = new java.util.HashMap<>();
        md.put("doc_id", docId);
        md.put("chunk_index", chunkIndex);
        if (bm25 != null) {
            md.put("keyword_score", bm25);
        }
        return Document.builder().id("c" + docId + "-" + chunkIndex).text("t")
                .metadata(md).score(score).build();
    }

    @Test
    void mergesSameChunkHitByBothPaths() {
        List<RetrievalCandidate> fused = RrfFuser.fuse(
                List.of(chunk(1L, 0, 0.9, null)),
                List.of(chunk(1L, 0, null, 5.0)));

        assertEquals(1, fused.size(), "同一 doc_id:chunk_index 只应留一条候选");
        RetrievalCandidate c = fused.get(0);
        assertEquals(1, c.semRank());
        assertEquals(1, c.kwRank());
        assertEquals(0.9, c.sem());
        assertEquals(5.0, c.kw());
    }

    @Test
    void doublePathHitOutranksSinglePathHead() {
        // RRF: doc3 = 1/63 + 1/62 ≈ 0.0320 > doc1 = doc9 = 1/61 ≈ 0.01639 > doc2 = 1/62 ≈ 0.01613
        List<RetrievalCandidate> fused = RrfFuser.fuse(
                List.of(chunk(1L, 0, 0.9, null), chunk(2L, 0, 0.8, null), chunk(3L, 0, 0.7, null)),
                List.of(chunk(9L, 0, null, 8.0), chunk(3L, 0, null, 6.0)));

        assertEquals(List.of(3L, 1L, 9L, 2L), fused.stream()
                .map(c -> DocumentMeta.docId(c.doc())).toList(),
                "双路命中应超过任一路榜首; 并列时保持语义路插入顺序");
        assertNull(DocumentMeta.similarity(fused.get(2).doc()), "doc9 只有关键词路命中, 无语义分");
    }

    @Test
    void keepsStableOrderOnTie() {
        List<RetrievalCandidate> fused = RrfFuser.fuse(
                List.of(chunk(1L, 0, 0.9, null), chunk(2L, 0, 0.8, null)),
                List.of());

        assertEquals(2, fused.size());
        assertEquals(1L, DocumentMeta.docId(fused.get(0).doc()).longValue(), "并列时保持语义路插入顺序");
    }

    @Test
    void emptyBothPathsYieldEmpty() {
        assertTrue(RrfFuser.fuse(List.of(), List.of()).isEmpty());
    }
}
