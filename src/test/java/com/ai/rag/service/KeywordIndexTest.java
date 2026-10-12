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

    /**
     * 启动重建键位回归: KeywordIndexRebuilder 按 Qdrant 滚动顺序分组注册, 列表下标 ≠ 原始
     * chunk_index。索引主键必须取分块自带的 chunk_index——否则 Hit.chunkIndex 与向量点元数据漂移,
     * RRF 两路去重对不上同一分块(实测事故: key=76:12 装着真序号 3 的年假块)。
     */
    @Test
    void registersByMetadataChunkIndexNotListPosition() {
        KeywordIndex index = new KeywordIndex();
        // 乱序到达(模拟 scroll 返回序): 真序号 3、1、2
        Document annualLeave = Document.builder().text("年假规定：入职满一年享有五天带薪年假。")
                .metadata(java.util.Map.of("chunk_index", 3)).build();
        Document workingTime = Document.builder().text("工作时间：周一至周五九点到十八点。")
                .metadata(java.util.Map.of("chunk_index", 1)).build();
        Document sickLeave = Document.builder().text("病假需提供医院证明，病假期间发放百分之六十基本工资。")
                .metadata(java.util.Map.of("chunk_index", 2)).build();
        index.addDocument(76L, "员工手册示例.md", List.of(annualLeave, workingTime, sickLeave));

        List<KeywordIndex.Hit> hits = index.search("年假规定", 5);
        assertFalse(hits.isEmpty());
        assertEquals(3, hits.get(0).chunkIndex(), "命中块的 chunkIndex 必须是其 metadata 原始序号");
        assertEquals("76:3", hits.get(0).key(), "索引主键应为 docId:{metadata chunk_index}");

        // 无 metadata 时回退列表下标(旧入库路径行为不变)
        KeywordIndex fallback = new KeywordIndex();
        fallback.addDocument(1L, "x.md", List.of(text("第一条"), text("第二条")));
        assertEquals("1:0", fallback.search("第一条", 1).get(0).key());
    }

    @Test
    void bm25RanksRelevantDocFirstAndRemovalWorks() {        KeywordIndex index = new KeywordIndex();
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
