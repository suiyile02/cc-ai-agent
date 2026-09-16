package com.ai.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现"重启自动重建后 BM25 检索"场景: 用与重建一致的方式注册分块,
 * 验证对真实用户问题能产生关键词命中。
 */
class KeywordIndexRebuildSearchTest {

    @Test
    void searchHitsAfterRebuildStyleRegistration() {
        KeywordIndex index = new KeywordIndex();
        String handbook = "# 《员工手册》示例知识库文档\n\n### 1.4 加班与调休\n"
                + "工作日加班按 1.5 倍工资核算，休息日加班可优先选择等时调休，调休额度需在 60 天内使用完毕，逾期未用按 2 倍工资结算。";
        index.addDocument(1L, "员工手册示例.md",
                List.of(Document.builder().text(handbook)
                        .metadata(java.util.Map.of("chunk_index", 0)).build()));

        List<KeywordIndex.Hit> hits = index.search("调休额度 60 天内使用完毕的规定是什么？", 10);

        assertTrue(!hits.isEmpty(), "BM25 应命中含调休的分块, 实际 0 命中");
    }
}
