package com.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CountBatchingStrategy} 单测: 嵌入分批必须按条数 ≤20(DashScope 兼容接口硬限),
 * 而非 Spring AI 默认的按 token——否则一个文档切出几十个小块时整批一次发过去被接口拒 400。
 */
class CountBatchingStrategyTest {

    private final CountBatchingStrategy strategy = new CountBatchingStrategy();

    private static Document doc(int i) {
        return Document.builder().text("小块" + i).build();
    }

    private static List<Document> docs(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(CountBatchingStrategyTest::doc).toList();
    }

    @Test
    void underTwentyStaysSingleBatch() {
        List<List<Document>> batches = strategy.batch(docs(15));

        assertEquals(1, batches.size());
        assertEquals(15, batches.get(0).size());
    }

    @Test
    void exactlyTwentyIsSingleBatch() {
        assertEquals(1, strategy.batch(docs(20)).size());
    }

    @Test
    void overTwentySplitsAtTwentyPerBatch() {
        List<List<Document>> batches = strategy.batch(docs(45));

        assertEquals(3, batches.size(), "45 条应拆为 20+20+5");
        assertEquals(20, batches.get(0).size());
        assertEquals(20, batches.get(1).size());
        assertEquals(5, batches.get(2).size());
    }

    @Test
    void allBatchesRespectTheHardLimit() {
        for (int n : new int[]{0, 1, 19, 20, 21, 50, 100}) {
            for (List<Document> batch : strategy.batch(docs(n))) {
                assertTrue(batch.size() <= 20, "任何一批都不得超过 20 条(DashScope 硬限): n=" + n);
            }
        }
    }

    @Test
    void emptyListYieldsNoBatch() {
        assertTrue(strategy.batch(docs(0)).isEmpty());
    }

    @Test
    void subBatchKeepsOriginalOrderAndContent() {
        List<List<Document>> batches = strategy.batch(docs(25));

        assertTrue(batches.get(0).get(0).getText().equals("小块0"));
        assertTrue(batches.get(1).get(4).getText().equals("小块24"), "跨批内容与顺序不得丢");
    }
}
