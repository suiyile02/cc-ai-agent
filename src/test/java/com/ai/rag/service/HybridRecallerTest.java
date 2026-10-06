package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.rag.RetrievalOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HybridRecaller} 语义路阈值测试。核心不变式：**阈值必须在本地过滤而不是交给向量库**,
 * 否则低于阈值的候选永不返回, 决策日志里只会看到 ≥阈值的分数(分布被底部截断),
 * 用它定标必然得出"阈值还能再提高"的错误结论。
 */
class HybridRecallerTest {

    private AppProperties props() {
        return new AppProperties();
    }

    @SuppressWarnings("unchecked")
    private HybridRecaller recaller(VectorStore store, KeywordIndex index) {
        return recaller(store, index, java.util.Set.of());
    }

    @SuppressWarnings("unchecked")
    private HybridRecaller recaller(VectorStore store, KeywordIndex index,
            java.util.Set<Long> disabled) {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        com.ai.knowledge.DocumentVisibility visibility = mock(com.ai.knowledge.DocumentVisibility.class);
        when(visibility.disabledDocIds()).thenReturn(disabled);
        return new HybridRecaller(provider, index, visibility, props());
    }

    /** 一个带相似度分的分块 */
    private static Document chunk(String text, double score) {
        return Document.builder().text(text).metadata(Map.of("file_name", "考勤与假期制度.md"))
                .score(score).build();
    }

    @Test
    void vectorQueryIsIssuedWithZeroThresholdSoSubThresholdScoresAreObservable() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("年假 10 天", 0.42)));
        HybridRecaller recaller = recaller(store, mock(KeywordIndex.class));

        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);

        recaller.recall("年假几天", 5);

        org.mockito.Mockito.verify(store).similaritySearch(sent.capture());
        assertEquals(0.0, sent.getValue().getSimilarityThreshold(),
                "向量库必须以 0 阈值召回, 把阈值判定留给本地——否则 0.42 这类候选永远不会被观察到");
    }

    @Test
    void recallKeepsSubThresholdChunksSoRerankCanSeeEveryCandidate() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("低于阈值的分块", 0.42)));
        HybridRecaller recaller = recaller(store, emptyKeywordIndex());

        HybridRecaller.Recall recall = recaller.recall("年假几天", 5);

        // 阈值过滤已从召回阶段移走(改到重排之后): 这一层必须原样交出候选,
        // 否则"余弦分偏低但确实答得上"的段落连被重排捞一次的机会都没有
        assertEquals(1, recall.semantic().size(), "召回层不得按阈值丢弃候选");
        assertEquals(0.42, recall.semanticMaxScore(), 1e-9,
                "最大分照常记录——出口判据与 P3-6 定标都读这个未截断的观察值");
    }

    @Test
    void aboveThresholdChunksPassThroughUnchanged() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("高相关", 0.61), chunk("次相关", 0.50)));
        HybridRecaller recaller = recaller(store, emptyKeywordIndex());

        HybridRecaller.Recall recall = recaller.recall("年假几天", 5);

        assertEquals(2, recall.semantic().size(), "两路结果原样透传");
        assertEquals(0.61, recall.semanticMaxScore(), 1e-9);
    }

    @Test
    void emptySemanticResultRecordsZeroMaxScore() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        HybridRecaller recaller = recaller(store, emptyKeywordIndex());

        HybridRecaller.Recall recall = recaller.recall("今天天气", 5);

        assertEquals(0.0, recall.semanticMaxScore(), 1e-9);
    }

    @Test
    void semanticFailureDegradesToEmptyWithoutFakeScore() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("连接被重置"));
        HybridRecaller recaller = recaller(store, emptyKeywordIndex());

        HybridRecaller.Recall recall = recaller.recall("年假几天", 5);

        assertTrue(recall.semantic().isEmpty());
        assertEquals(0.0, recall.semanticMaxScore(), 1e-9);
    }

    /* ---------------- 禁用文档过滤 ---------------- */

    /** 构造带 doc_id 元数据的分块(模拟入库写入的 payload) */
    private static Document docWithId(long docId, String text, double score) {
        return Document.builder().text(text)
                .metadata(java.util.Map.of("doc_id", String.valueOf(docId),
                        "file_name", "考勤与假期制度.md"))
                .score(score).build();
    }

    @Test
    void disabledDocIsPushedDownAsQdrantFilterNotPostFiltered() throws Exception {
        // 语义路: 禁用集必须下推为查询过滤条件(后过滤会让禁用文档占满 Top-K, 静默缩水有效 K)
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        HybridRecaller recaller = recaller(store, emptyKeywordIndex(),
                java.util.Set.of(7L));

        recaller.recall("年假几天", 5);

        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(store).similaritySearch(sent.capture());
        org.springframework.ai.vectorstore.filter.Filter.Expression expr =
                (org.springframework.ai.vectorstore.filter.Filter.Expression) sent.getValue()
                        .getFilterExpression();
        assertEquals(org.springframework.ai.vectorstore.filter.Filter.ExpressionType.NIN,
                expr.type(), "过滤语义必须是 NOT IN(doc_id)");
        org.springframework.ai.vectorstore.filter.Filter.Value value =
                (org.springframework.ai.vectorstore.filter.Filter.Value) expr.right();
        assertEquals(List.of("7"), (List<?>) value.value(),
                "过滤值为字符串形式的 docId(payload 中 doc_id 为字符串存储)");
    }

    @Test
    void emptyDisabledSetIssuesNoFilter() throws Exception {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        HybridRecaller recaller = recaller(store, emptyKeywordIndex(), java.util.Set.of());

        recaller.recall("年假几天", 5);

        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(store).similaritySearch(sent.capture());
        assertEquals(null, sent.getValue().getFilterExpression(),
                "无禁用文档时不得附加过滤条件");
    }

    @Test
    void keywordPathPostFiltersDisabledDocsButKeepsMissingDocId() {
        // BM25 路召回后过滤: 禁用文档剔除, 缺 doc_id 的点保守保留
        KeywordIndex index = mock(KeywordIndex.class);
        when(index.isEmpty()).thenReturn(false);
        when(index.search(any(), org.mockito.Mockito.anyInt())).thenReturn(List.of(
                new KeywordIndex.Hit("k1", 7L, "已禁用.md", 0, "禁用内容", 3.0),
                new KeywordIndex.Hit("k2", 8L, "正常.md", 1, "正常内容", 2.0)));
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        HybridRecaller recaller = recaller(store, index, java.util.Set.of(7L));

        HybridRecaller.Recall recall = recaller.recall("查询", 5);

        assertEquals(1, recall.keyword().size(), "禁用文档被后过滤剔除, 正常文档保留");
        assertEquals("正常.md", String.valueOf(recall.keyword().get(0).getMetadata().get("file_name")));
        // 语义路同样收到下推过滤(禁用集非空)
        ArgumentCaptor<SearchRequest> sent = ArgumentCaptor.forClass(SearchRequest.class);
        org.mockito.Mockito.verify(store).similaritySearch(sent.capture());
        assertTrue(sent.getValue().getFilterExpression() != null);
    }

    /** 关键词索引置空以隔离语义路(hybrid 开启但索引空 → 该路不产出) */
    private static KeywordIndex emptyKeywordIndex() {
        KeywordIndex index = mock(KeywordIndex.class);
        when(index.isEmpty()).thenReturn(true);
        return index;
    }
}
