package com.ai.rag.service;

import com.ai.common.HeuristicTokenCounter;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.rag.RetrievalOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagRetrievalService} 检索阶段超时保护测试：嵌入/向量库挂起时必须在预算内降级,
 * 不能把对话请求拖住(实测线上出现过单轮检索卡 100s)。
 */
class RagRetrievalTimeoutTest {

    /** 构造带指定检索预算的配置 */
    private AppProperties props(long timeoutMs) {
        AppProperties p = new AppProperties();
        p.getRag().setRetrieveTimeoutMs(timeoutMs);
        return p;
    }

    /** 构造被测服务(向量库由 ObjectProvider 提供, 关键词索引为空实现) */
    @SuppressWarnings("unchecked")
    private RagRetrievalService service(VectorStore store, AppProperties props) {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return new RagRetrievalService(provider, props, mock(KeywordIndex.class),
                mock(ChatClientProvider.class), new HeuristicTokenCounter());
    }

    @Test
    void hangingVectorStoreDegradesWithinBudget() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(inv -> {
            Thread.sleep(5_000); // 模拟挂起的嵌入/向量库调用
            return List.of();
        });
        RagRetrievalService svc = service(store, props(300));

        long start = System.currentTimeMillis();
        RetrievalOutcome outcome = svc.retrieveOutcome("年假有多少天", 5, 0.45);
        long cost = System.currentTimeMillis() - start;

        assertTrue(outcome.hits().isEmpty(), "超时降级不得返回命中");
        assertTrue(outcome.executed(), "超时降级须记为已执行, 否则会被审计误判为语义缓存命中");
        assertTrue(outcome.degraded(), "超时降级须标记 degraded, 否则语义负缓存会误判为真零命中");
        assertTrue(cost < 3_000, "应在预算内降级返回, 实际 " + cost + "ms");
    }

    @Test
    void fastRetrievalStillReturnsHits() {
        VectorStore store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(Document.builder().text("年假 10 天")
                        .metadata(Map.of("file_name", "a.md", "doc_id", "1", "chunk_index", 0))
                        .build()));
        RagRetrievalService svc = service(store, props(10_000));

        RetrievalOutcome outcome = svc.retrieveOutcome("年假有多少天", 5, 0.45);

        assertEquals(1, outcome.hits().size(), "正常链路不受限时保护影响");
        assertEquals(1, outcome.semanticCount());
        assertTrue(outcome.executed());
        assertFalse(outcome.degraded());
    }
}
