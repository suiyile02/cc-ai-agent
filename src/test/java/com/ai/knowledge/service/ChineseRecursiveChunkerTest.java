package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import com.ai.knowledge.chunking.ChineseRecursiveChunker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChineseRecursiveChunker} 单元测试: 中文标点回退切分、块尾停在完整句、
 * 无标点文本硬切兜底(不丢内容)、相邻块 overlap。
 */
class ChineseRecursiveChunkerTest {

    private AppProperties props(int chunkSize, int overlapTokens) {
        AppProperties p = new AppProperties();
        IngestionProps.ChunkingStrategyProps sp = new IngestionProps.ChunkingStrategyProps();
        sp.setChunkSize(chunkSize);
        sp.setOverlapTokens(overlapTokens);
        p.getIngestion().getChunking().getStrategies().put("recursive-cn", sp);
        return p;
    }

    private Document doc(String text) {
        return Document.builder().text(text).build();
    }

    @Test
    void shortTextIsSingleChunk() {
        ChineseRecursiveChunker chunker = new ChineseRecursiveChunker(props(512, 0));

        List<Document> chunks = chunker.split(doc("这是一段很短的文字。"));

        assertEquals(1, chunks.size());
        assertEquals("这是一段很短的文字。", chunks.get(0).getText());
    }

    @Test
    void longChineseTextSplitsAtSentenceBoundaries() {
        // 构造 400 句, 每句约 12 字 → 远超 chunkSize(字符数近似目标)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("这是第").append(i).append("句测试内容。");
        }
        ChineseRecursiveChunker chunker = new ChineseRecursiveChunker(props(120, 0));

        List<Document> chunks = chunker.split(doc(sb.toString()));

        assertTrue(chunks.size() > 1, "长文本必须切成多块");
        // 除末块外, 每块必须以句末标点收尾(块尾停在完整句, 不腰斩)
        for (int i = 0; i < chunks.size() - 1; i++) {
            String t = chunks.get(i).getText();
            assertTrue(t.endsWith("。"), "第 " + i + " 块应以句号收尾: " + t.substring(t.length() - 8));
        }
        // 内容不丢: 拼接后句数一致
        String joined = String.join("", chunks.stream().map(Document::getText).toList());
        assertEquals(400, joined.split("。", -1).length - 1, "所有句子必须保留");
    }

    @Test
    void punctuationFreeTextFallsBackToHardCutWithoutLoss() {
        // 连续 "a" 无任何标点/空格: 必须硬切兜底, 且不丢字符
        String text = "a".repeat(1000);
        ChineseRecursiveChunker chunker = new ChineseRecursiveChunker(props(100, 0));

        List<Document> chunks = chunker.split(doc(text));

        assertTrue(chunks.size() >= 10);
        assertEquals(1000, String.join("", chunks.stream().map(Document::getText).toList()).length(),
                "无标点文本硬切兜底不得丢内容");
    }

    @Test
    void overlapDuplicatesTailOfPreviousChunk() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("第").append(i).append("句内容若干字。");
        }
        String text = sb.toString();
        ChineseRecursiveChunker chunker = new ChineseRecursiveChunker(props(100, 30));

        List<Document> chunks = chunker.split(doc(text));

        assertTrue(chunks.size() > 1);
        // 第 2 块开头应是第 1 块尾部的重叠部分(公共子串校验: 第 2 块前若干字符出现在上块尾部)
        String prev = chunks.get(0).getText();
        String next = chunks.get(1).getText();
        String probe = next.substring(0, Math.min(20, next.length()));
        assertTrue(prev.contains(probe.substring(Math.max(0, probe.length() - 10))),
                "开启 overlap 时相邻块应有重叠: 下一块开头片段应取自上一块尾部");
    }

    @Test
    void zeroOverlapMeansNoDuplication() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("第").append(i).append("句内容若干字。");
        }
        ChineseRecursiveChunker chunker = new ChineseRecursiveChunker(props(100, 0));

        List<Document> chunks = chunker.split(doc(sb.toString()));

        // 无重叠时, 按句切分的结果拼接后不应有重复句
        String joined = String.join("", chunks.stream().map(Document::getText).toList());
        assertEquals(joined.indexOf("第5句内容若干字。"), joined.lastIndexOf("第5句内容若干字。"),
                "零 overlap 时不应出现重复句");
    }
}
