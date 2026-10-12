package com.ai.rag.service;

import com.ai.common.HeuristicTokenCounter;
import com.ai.config.AppProperties;
import com.ai.knowledge.ParentBlockStore;
import com.ai.rag.RetrievalOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrievalService} 证据分层取数测试(P3-10 分层口径)。
 *
 * <p>核心不变式：<b>注入顺序与出口判据同口径</b>——出口只认"语义路过阈值"，注入也必须让
 * 过阈值的候选整体优先；仅词面命中(归一化 BM25 榜首恒为 1.0)只能补位，且证据存在时封顶。
 * 旧口径下词面噪声系统性压过语义真答案并吃满 Top-K，正是"文档里有答案却答非所问"的来源。
 *
 * <p>无证据轮次(闲聊/无关)不受分层影响：补满 topK 的旧行为保留, 答/拒仍由
 * {@code semanticMaxScore} 判定——分层只改注入组成, 不改出口结论。
 */
class RagRetrievalEvidenceLevelTest {

    private final HybridRecaller recaller = mock(HybridRecaller.class);
    private final ParentBlockStore parentBlockStore = mock(ParentBlockStore.class);
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getRag().setRetrieveTimeoutMs(0); // 直接执行, 不包限时线程
        service = new RagRetrievalService(props, recaller,
                new RerankStrategyFactory(List.of(new ScoreFusionReranker()), props),
                new RagContextRenderer(props, new HeuristicTokenCounter()), parentBlockStore,
                new HeuristicTokenCounter());
    }

    /** 语义路命中(分数在 score 字段, 与召回层契约一致; 无 parent_ref 则跳过父块展开) */
    private static Document semantic(String text, long docId, double score) {
        return Document.builder().text(text).score(score)
                .metadata(Map.of("file_name", "a.md", "doc_id", docId, "chunk_index", 0))
                .build();
    }

    /** 关键词路命中(无 score, 分数挂在 keyword_score 元数据, 与 KeywordIndex 契约一致) */
    private static Document keyword(String text, long docId, double kwScore) {
        return Document.builder().text(text)
                .metadata(Map.of("file_name", "b.md", "doc_id", docId, "chunk_index", 0,
                        "keyword_score", kwScore))
                .build();
    }

    private void recallReturns(List<Document> semantic, List<Document> keyword) {
        double max = semantic.stream().map(Document::getScore).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.0);
        when(recaller.recall(anyString(), anyInt()))
                .thenReturn(new HybridRecaller.Recall(semantic, keyword, max, true, !keyword.isEmpty()));
    }

    @Test
    void evidencedCandidateOutranksHigherFusedKeywordOnlyCandidate() {
        // 纯词面命中批内归一化后 fused=1.0 > 语义真答案 0.60——旧口径下噪声排第一
        recallReturns(List.of(semantic("语义真答案", 1L, 0.60)),
                List.of(keyword("纯词面噪声", 2L, 5.0)));

        RetrievalOutcome out = service.retrieveOutcome("报销上限是多少", 5, 0.45);

        assertEquals("语义真答案", out.hits().get(0).getText(),
                "分层后证据必须排在仅词面命中之前(注入顺序与出口判据同口径)");
        assertEquals(2, out.hits().size(), "非证据候选仍补位(证据不足时上下文不空窗)");
    }

    @Test
    void keywordOnlyFillIsCappedWhenEvidenceExists() {
        List<Document> keywordDocs = List.of(
                keyword("词面1", 10L, 5.0), keyword("词面2", 11L, 4.0),
                keyword("词面3", 12L, 3.0), keyword("词面4", 13L, 2.0),
                keyword("词面5", 14L, 1.0));
        recallReturns(List.of(semantic("证据A", 1L, 0.60), semantic("证据B", 2L, 0.55)), keywordDocs);

        RetrievalOutcome out = service.retrieveOutcome("报销标准", 5, 0.45);

        assertEquals(4, out.hits().size(), "2 条证据 + 封顶 2 条词面补位, 噪声不得吃满 topK");
        assertEquals("证据A", out.hits().get(0).getText());
        assertEquals("证据B", out.hits().get(1).getText());
        assertEquals(List.of("词面1", "词面2"),
                out.hits().subList(2, 4).stream().map(Document::getText).toList(),
                "补位按融合分取最高名次的两条");
    }

    @Test
    void withoutEvidenceKeywordFillStillFillsTopK() {
        // 天气/闲聊类: 语义最高分未过阈值 → 全部候选都是非证据 → 保持旧行为补满(出口仍判拒答)
        List<Document> keywordDocs = List.of(
                keyword("词面1", 10L, 6.0), keyword("词面2", 11L, 5.0),
                keyword("词面3", 12L, 4.0), keyword("词面4", 13L, 3.0),
                keyword("词面5", 14L, 2.0), keyword("词面6", 15L, 1.0));
        recallReturns(List.of(semantic("低分语义", 1L, 0.30)), keywordDocs);

        RetrievalOutcome out = service.retrieveOutcome("今天天气怎么样", 5, 0.45);

        assertEquals(5, out.hits().size(), "无证据轮次分层不缩水: 词面补满 topK(出口判据不受影响)");
    }

    @Test
    void overFetchedSemanticIsConvergedBackWhenKeywordPathIsEmpty() {
        List<Document> many = IntStream.range(0, 15)
                .mapToObj(i -> semantic("块" + i, 100L + i, 0.9))
                .toList();
        recallReturns(many, List.of());

        RetrievalOutcome out = service.retrieveOutcome("年假有几天", 5, 0.45);

        assertEquals(5, out.hits().size(),
                "无关键词路时不走融合, 语义过取必须收敛回 topK(topK=5 时不能注入 15 段)");
    }
}
