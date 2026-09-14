package com.ai.rag.service;
import com.ai.rag.RetrievalOutcome;

import com.ai.config.AppProperties;
import com.ai.common.HeuristicTokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import com.ai.config.ChatClientProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link RagRetrievalService#buildContext(List, int)} 单元测试：Token 预算内组装与超预算截断。
 */
class RagRetrievalServiceContextTest {

    @SuppressWarnings("unchecked")
    private RagRetrievalService newService(AppProperties props) {
        return new RagRetrievalService(mock(ObjectProvider.class), props,
                mock(KeywordIndex.class), mock(ChatClientProvider.class), new HeuristicTokenCounter());
    }

    private Document doc(String file, String text) {
        return Document.builder().text(text).metadata(Map.of("file_name", file)).build();
    }

    @Test
    void buildsContextWithinTokenBudget() {
        RagRetrievalService svc = newService(new AppProperties());
        List<Document> hits = List.of(
                doc("a.md", "年假制度内容一二三四五"),
                doc("b.md", "报销标准内容六七八九十"));
        String ctx = svc.buildContext(hits, 10000);
        assertTrue(ctx.contains("a.md"));
        assertTrue(ctx.contains("b.md"));
    }

    @Test
    void truncatesWhenOverTokenBudget() {
        RagRetrievalService svc = newService(new AppProperties());
        List<Document> hits = List.of(
                doc("a.md", "年假制度".repeat(50)),
                doc("b.md", "报销标准".repeat(50)));
        String ctx = svc.buildContext(hits, 20);
        assertTrue(ctx.contains("Token 预算截断"));
        assertFalse(ctx.contains("b.md"), "超预算后不应再纳入后续文档");
    }

    @Test
    void emptyHitsYieldEmptyContext() {
        RagRetrievalService svc = newService(new AppProperties());
        assertTrue(svc.buildContext(List.of(), 100).isEmpty());
    }
}
