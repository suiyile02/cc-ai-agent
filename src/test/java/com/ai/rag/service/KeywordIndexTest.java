package com.ai.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KeywordIndex(BM25) 单元测试：分词、排序、删除、幂等重建。
 */
class KeywordIndexTest {

    private Document text(String content) {
        return Document.builder().text(content).build();
    }

    @Test
    void bm25RanksRelevantDocFirstAndRemovalWorks() {
        KeywordIndex index = new KeywordIndex();
        index.addDocument(1L, "员工手册.md", List.of(
                text("年假政策：入职满一年享有五天带薪年假，年假需在自然年内使用。"),
                text("考勤制度：工作时间为周一至周五九点到十八点。")));
        index.addDocument(2L, "报销制度.md", List.of(
                text("差旅报销：高铁二等座实报实销，住宿每日上限五百元。")));

        assertEquals(3, index.size());

        List<KeywordIndex.Hit> hits = index.search("年假可以休几天", 5);
        assertFalse(hits.isEmpty(), "应命中年假相关文档");
        assertEquals(1L, hits.get(0).docId(), "包含年假关键词的文档应排在最前");

        index.removeDocument(1L);
        assertEquals(1, index.size());
        assertTrue(index.search("年假", 5).isEmpty(), "删除后不应再命中该文档");

        // 幂等重建
        index.addDocument(1L, "员工手册.md", List.of(text("年假政策：同上述规定。")));
        assertEquals(2, index.size());
        assertFalse(index.search("年假", 5).isEmpty());
    }

    @Test
    void tokenizerCoversLatinAndChineseBigrams() {
        List<String> terms = KeywordIndex.termsOf("Hello AI 年假制度 报销2025");
        assertTrue(terms.contains("hello"));
        assertTrue(terms.contains("ai"));
        assertTrue(terms.contains("报销"));
        assertTrue(terms.contains("年假"));
        assertTrue(terms.contains("2025"));
    }
}
