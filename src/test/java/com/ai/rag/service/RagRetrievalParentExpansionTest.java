package com.ai.rag.service;

import com.ai.common.HeuristicTokenCounter;
import com.ai.config.AppProperties;
import com.ai.knowledge.ParentBlockStore;
import com.ai.rag.RetrievalOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrievalService} 父子文档(小-大检索)展开测试: 召回/注入粒度分离——
 * 排序、阈值、出口判据全部按小块分数走, 喂给模型的是父块全文; 父块缺失/读取失败保留小块原文。
 */
class RagRetrievalParentExpansionTest {

    private final HybridRecaller recaller = mock(HybridRecaller.class);
    private final ParentBlockStore parentBlockStore = mock(ParentBlockStore.class);
    private RagRetrievalService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getRag().setRetrieveTimeoutMs(0); // 直接执行, 不包限时线程
        service = new RagRetrievalService(props, recaller, mock(RerankStrategyFactory.class),
                new RagContextRenderer(props, new HeuristicTokenCounter()), parentBlockStore);
    }

    /** 语义路小块: doc/parent_ref 用 Number(向量库回填类型), 携带语义分以便过阈值。 */
    private static Document small(String text, Object docId, Object parentRef) {
        return Document.builder().id("pt-" + text.hashCode()).text(text)
                .metadata(new java.util.HashMap<>(Map.of(
                        "doc_id", docId, "parent_ref", parentRef,
                        "file_name", "制度.md", "chunk_index", 5, "semantic_score", 0.61)))
                .build();
    }

    private void recallReturns(Document... hits) {
        when(recaller.recall(anyString(), anyInt()))
                .thenReturn(new HybridRecaller.Recall(List.of(hits), List.of(), 0.61, true, false));
    }

    @Test
    void hitWithParentRefIsExpandedToParentTextAndVerdictUnaffected() {
        recallReturns(small("小块: 报销标准为 500 元。", 7L, 2));
        when(parentBlockStore.findParents(anyLong(), anyList()))
                .thenReturn(Map.of(2, "父块全文: 差旅费报销制度……报销标准为 500 元。"));

        RetrievalOutcome out = service.retrieveOutcome("报销标准", 5, 0.45);

        assertEquals(1, out.hits().size());
        Document hit = out.hits().get(0);
        assertEquals("父块全文: 差旅费报销制度……报销标准为 500 元。", hit.getText(),
                "注入文本必须是父块(上下文粒度是父块, 检索粒度才是小块)");
        // 来源与分数元数据保留(展示来源仍指向命中小块, 排序依据不变)
        assertEquals("制度.md", hit.getMetadata().get("file_name"));
        assertEquals(2, hit.getMetadata().get("parent_ref"));
        // 出口判据不受展开影响: 观察值是未展开的语义路最大分与计数
        assertEquals(0.61, out.semanticMaxScore(), 1e-9);
        assertEquals(1, out.semanticCount());
        assertTrue(out.executed());
    }

    @Test
    void missingParentFallsBackToSmallChunkText() {
        recallReturns(small("小块原文", 7L, 2));
        when(parentBlockStore.findParents(anyLong(), anyList())).thenReturn(Map.of());

        RetrievalOutcome out = service.retrieveOutcome("问题", 5, 0.45);

        assertEquals("小块原文", out.hits().get(0).getText(),
                "父块缺失不得降级为无资料: 保留小块原文注入");
    }

    @Test
    void hitsWithoutParentRefSkipParentLookupEntirely() {
        Document noRef = Document.builder().text("无父块文档的小块").score(0.9)
                .metadata(Map.of("doc_id", 3L, "file_name", "b.txt", "chunk_index", 0,
                        "semantic_score", 0.9))
                .build();
        recallReturns(noRef);

        RetrievalOutcome out = service.retrieveOutcome("问题", 5, 0.45);

        assertSame(noRef, out.hits().get(0), "无 parent_ref 时原样返回(零开销)");
        verifyNoInteractions(parentBlockStore);
    }

    @Test
    void parentLookupIsBatchedPerDocAndSeqDeduplicated() {
        recallReturns(
                small("命中小块1", 7L, 2),
                small("命中小块2", 7L, 2),   // 同文档同父块: 不得重复查询
                small("命中小块3", 7L, 3),
                small("其它文档", 9L, 0));
        when(parentBlockStore.findParents(eq(7L), anyList()))
                .thenReturn(Map.of(2, "父块2", 3, "父块3"));
        when(parentBlockStore.findParents(eq(9L), anyList()))
                .thenReturn(Map.of(0, "父块9"));

        RetrievalOutcome out = service.retrieveOutcome("问题", 5, 0.45);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Integer>> seqs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Long> docIds = ArgumentCaptor.forClass(Long.class);
        verify(parentBlockStore, times(2)).findParents(docIds.capture(), seqs.capture());
        Map<Long, Set<Integer>> requested = new java.util.HashMap<>();
        for (int i = 0; i < docIds.getAllValues().size(); i++) {
            requested.put(docIds.getAllValues().get(i), new HashSet<>(seqs.getAllValues().get(i)));
        }
        assertEquals(Map.of(7L, Set.of(2, 3), 9L, Set.of(0)), requested,
                "按文档合并成一次批量查询, 且父块序号去重(同父块三命中只查一次)");
        assertEquals(List.of("父块2", "父块2", "父块3", "父块9"),
                out.hits().stream().map(Document::getText).toList(),
                "重复命中的小块各自展开为同一父块文本");
    }

    @Test
    void subThresholdHitIsDroppedBeforeParentLookup() {
        Document low = Document.builder().text("低分小块").metadata(new java.util.HashMap<>(Map.of(
                        "doc_id", 7L, "parent_ref", 2, "file_name", "制度.md",
                        "chunk_index", 5, "semantic_score", 0.30)))
                .build();
        recallReturns(low);

        RetrievalOutcome out = service.retrieveOutcome("问题", 5, 0.45);

        assertTrue(out.hits().isEmpty(), "0.30 < 0.45 不得进上下文");
        verifyNoInteractions(parentBlockStore);
    }
}
