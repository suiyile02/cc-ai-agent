package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.rag.OutcomeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阈值过滤时机的契约测试：过滤发生在<b>重排之后</b>，且只裁语义路低分候选。
 *
 * <p>改造前是"召回即过滤", 后果不是分数不好看, 而是<b>第 6 名那种低余弦分但真正答得上的段落
 * 连被重排捞一次的机会都没有</b>——短查询与跨词表说法正是高发场景。出口判据读的是
 * {@code semanticMaxScore}(与过滤时机无关), 所以本组用例同时锁住两件事:
 * 注入集合按阈值收敛、出口结论不受影响。
 */
class RagRetrievalThresholdTest {

    /** 语义路分块(带 file_name/doc_id/chunk_index 以便算 chunkKey 与取分) */
    private static Document semantic(String text, double score) {
        return Document.builder().text(text).score(score)
                .metadata(Map.of("file_name", "a.md", "doc_id", "1",
                        "chunk_index", Math.abs(text.hashCode() % 100)))
                .build();
    }

    private final AppProperties props = new AppProperties();

    @Test
    void subThresholdSemanticOnlyHitsAreDroppedFromInjection() {
        List<Document> hits = List.of(semantic("过阈值", 0.61), semantic("差一点", 0.42));

        List<Document> kept = RagRetrievalService.filterByThreshold(hits, 0.45);

        assertEquals(1, kept.size(), "0.42 < 0.45 不得进上下文");
        assertEquals("过阈值", kept.get(0).getText());
    }

    @Test
    void rerankedCandidatesAreJudgedByTheSemanticScoreCarriedInMetadata() {
        // 重排后 score 字段被融合分/模型相关度分占用, 语义原始分只挂在 semantic_score 上;
        // 过滤器读的是后者——所以"融合分很高但余弦分未过线"的候选必须被裁掉
        Document reranked = RetrievalCandidateOf.build(semantic("原始余弦 0.30", 0.30), 0.95);

        List<Document> kept = RagRetrievalService.filterByThreshold(List.of(reranked), 0.45);

        assertTrue(kept.isEmpty(),
                "不得拿融合分 0.95 当依据: 阈值判的是语义相关度, 读错字段等于阈值失效");
        assertEquals(0.95, DocumentMeta.similarity(reranked), 1e-9,
                "前置确认: score 字段此刻装的是重排分(不是语义分), 这正是不能用它的理由");
    }

    @Test
    void keywordOnlyHitsAreNotJudgedByTheSemanticThreshold() {
        // 纯 BM25 命中没有语义分; 按 0 处理等于"词面命中一律不算依据", 但它已在重排中竞争过名次
        Document keywordOnly = Document.builder().text("仅词面命中")
                .metadata(Map.of("file_name", "b.md", "doc_id", "2", "chunk_index", 0,
                        "keyword_score", 3.7))
                .build();

        List<Document> kept = RagRetrievalService.filterByThreshold(List.of(keywordOnly), 0.45);

        assertEquals(1, kept.size(), "无语义分的候选保留(与历史口径一致): 缺分多为向量库未回填, 不该整路清零");
    }

    /** 造一个"带重排分"的文档, 复用生产路径(toDocument)以保证 metadata 键名一致。 */
    private static final class RetrievalCandidateOf {
        static Document build(Document doc, double fused) {
            return new RetrievalCandidate(doc, DocumentMeta.similarity(doc), null, 1, null)
                    .withFused(fused).toDocument();
        }
    }

    @Test
    void thresholdDoesNotChangeOutcomeVerdictBecauseItReadsMaxScore() {
        // 出口判据读的是 semanticMaxScore(未截断的观察值)与 hits, 因此"过滤挪到重排后"不改变结论:
        // 低分轮次无论过滤在哪一步都拿不到 grounded, 高分轮次两边都给得出 grounded
        double threshold = 0.45;
        var refused = new com.ai.rag.RetrievalOutcome(List.of(), true, 0, 0, false, 0.42);
        assertEquals(com.ai.rag.ChatOutcome.REFUSED_NO_EVIDENCE,
                OutcomeResolver.resolve(refused, false, true, threshold),
                "最大分 0.42 未过阈值 → 无据拒答");

        var grounded = new com.ai.rag.RetrievalOutcome(
                List.of(semantic("过阈值", 0.61)), true, 1, 0, false, 0.61);
        assertEquals(com.ai.rag.ChatOutcome.ANSWERED_FROM_KB,
                OutcomeResolver.resolve(grounded, false, true, threshold),
                "过阈值且有命中 → 依据知识库作答");
    }
}
