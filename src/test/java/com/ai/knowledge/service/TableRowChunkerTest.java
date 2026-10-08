package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TableRowChunker} 单元测试: 一行一块(列名内嵌)、表头不单独成块、行组父块序号、
 * CSV 引号/空单元格/单列/多行字段、Excel sheet 形状、超长行再切不丢内容。
 */
class TableRowChunkerTest {

    private AppProperties props(int rowsPerParent, boolean hasHeader) {
        AppProperties p = new AppProperties();
        IngestionProps.ChunkingStrategyProps sp = new IngestionProps.ChunkingStrategyProps();
        sp.setChunkSize(512);
        sp.setRowsPerParent(rowsPerParent);
        sp.setHasHeader(hasHeader);
        p.getIngestion().getChunking().getStrategies().put("table-row", sp);
        return p;
    }

    private List<Document> split(String text) {
        return new TableRowChunker(props(10, true)).split(Document.builder()
                .text(text)
                .metadata(new HashMap<>(Map.of("source", "policies.csv")))
                .build());
    }

    private static String joined(List<Document> chunks) {
        return chunks.stream().map(Document::getText).collect(Collectors.joining("\n"));
    }

    private static int parentSeq(Document d) {
        Object seq = d.getMetadata().get(ChunkingStrategy.PARENT_SEQ_KEY);
        assertTrue(seq instanceof Number, "每个小块必须携带 " + ChunkingStrategy.PARENT_SEQ_KEY);
        return ((Number) seq).intValue();
    }

    private static List<Integer> parentSeqs(List<Document> chunks) {
        return chunks.stream().map(TableRowChunkerTest::parentSeq).toList();
    }

    @Test
    void csvRowsAreRenderedWithColumnNamesAndQuotedFieldKeptIntact() {
        List<Document> chunks = split("等级,住宿标准,交通标准\n"
                + "经理,500元,高铁二等座\n"
                + "总监,\"800元,含补贴\",飞机经济舱\n");

        assertEquals(2, chunks.size(), "表头不单独成块: 2 个数据行 → 2 块");
        assertEquals("policies 第2行: 等级=经理 | 住宿标准=500元 | 交通标准=高铁二等座",
                chunks.get(0).getText(), "列名必须内嵌进行文本(裸行匹配不到'住宿标准')");
        // 引号内的逗号是字段内容, 裸 split(",") 会把它切成两列
        assertTrue(chunks.get(1).getText().contains("住宿标准=800元,含补贴"),
                "带引号字段必须整体保留: " + chunks.get(1).getText());
        assertFalse(chunks.get(1).getText().contains("含补贴=飞机经济舱"),
                "引号内的逗号不得当分隔符");
    }

    @Test
    void emptyCellsAreSkippedInRendering() {
        List<Document> chunks = split("等级,住宿标准,交通标准\n经理,500元,\n");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().contains("等级=经理"));
        assertTrue(chunks.get(0).getText().contains("住宿标准=500元"));
        assertFalse(chunks.get(0).getText().contains("交通标准="), "空单元格不渲染");
    }

    @Test
    void spreadsheetShapeSplitsSheetsAndKeepsSheetNamePrefix() {
        List<Document> chunks = split("差旅标准\n"
                + "\t等级\t住宿标准\t交通标准\n"
                + "\t经理\t500元\t高铁二等座\n"
                + "\n"
                + "补贴标准\n"
                + "\t地区\t日补\n"
                + "\t北上广\t120\n");

        assertEquals(2, chunks.size(), "两个 sheet 各一行数据");
        assertTrue(chunks.get(0).getText().startsWith("差旅标准 第3行:"),
                "sheet 名作为前缀(多 sheet 消歧): " + chunks.get(0).getText());
        assertTrue(chunks.get(0).getText().contains("住宿标准=500元"));
        assertTrue(chunks.get(1).getText().startsWith("补贴标准 第7行:"),
                "第二个 sheet 的表头/数据行按原文行号: " + chunks.get(1).getText());
        assertTrue(chunks.get(1).getText().contains("日补=120"));
    }

    @Test
    void rowsPerParentGroupsRowsIntoSharedParentSeq() {
        StringBuilder sb = new StringBuilder("编号,说明\n");
        for (int i = 1; i <= 25; i++) {
            sb.append(i).append(",内容").append(i).append("\n");
        }
        List<Document> chunks = new TableRowChunker(props(10, true)).split(Document.builder()
                .text(sb.toString())
                .metadata(new HashMap<>(Map.of("source", "t.csv")))
                .build());

        assertEquals(25, chunks.size(), "25 个数据行 → 25 个小块");
        List<Integer> seqs = parentSeqs(chunks);
        assertEquals(10, seqs.stream().filter(s -> s == 0).count(), "前 10 行共享父块 0");
        assertEquals(10, seqs.stream().filter(s -> s == 1).count(), "中 10 行共享父块 1");
        assertEquals(5, seqs.stream().filter(s -> s == 2).count(), "末 5 行共享父块 2");
        assertTrue(chunks.get(0).getText().contains("说明=内容1"), "小块文本含列名=值");
    }

    @Test
    void hasHeaderFalseTreatsFirstRowAsData() {
        List<Document> chunks = new TableRowChunker(props(10, false)).split(Document.builder()
                .text("等级,住宿标准\n经理,500元\n")
                .metadata(new HashMap<>(Map.of("source", "t.csv")))
                .build());

        assertEquals(2, chunks.size(), "无表头: 首行也是数据行");
        assertTrue(chunks.get(0).getText().contains("列1=等级 | 列2=住宿标准"),
                "无表头时列名用通行序号: " + chunks.get(0).getText());
        assertTrue(chunks.get(1).getText().contains("列1=经理 | 列2=500元"));
    }

    @Test
    void allCellValuesArePreservedAcrossChunks() {
        StringBuilder sb = new StringBuilder("等级,住宿标准\n");
        for (int i = 0; i < 30; i++) {
            sb.append("等级").append(i).append(",").append(i).append("00元\n");
        }
        List<Document> chunks = split(sb.toString());

        String all = joined(chunks);
        for (int i = 0; i < 30; i++) {
            assertTrue(all.contains("等级=等级" + i), "值不得丢失: 等级" + i);
            assertTrue(all.contains("住宿标准=" + i + "00元"), "值不得丢失: " + i + "00元");
        }
    }

    @Test
    void oversizeRowIsSplitIntoMultiplePiecesSharingParentSeq() {
        // 单行的大描述列超过 chunk-size: 标点回退再切, 各片共享同一父块
        StringBuilder big = new StringBuilder("名称,说明\n甲,");
        for (int i = 0; i < 200; i++) {
            big.append("这是第").append(i).append("句说明内容,足够长以触发再次切分。");
        }
        big.append("\n");
        List<Document> chunks = split(big.toString());

        assertTrue(chunks.size() > 1, "超长行必须再切");
        assertEquals(java.util.Set.of(0),
                chunks.stream().map(TableRowChunkerTest::parentSeq).collect(Collectors.toSet()),
                "同一行的各片共享父块");
        String all = joined(chunks);
        assertTrue(all.contains("这是第0句"), "行首内容保留");
        assertTrue(all.contains("这是第199句"), "行尾内容不丢");
    }

    @Test
    void singleColumnCsvUsesHeaderAsColumnName() {
        List<Document> chunks = split("名称\n甲\n乙\n");

        assertEquals(2, chunks.size());
        assertEquals("policies 第2行: 名称=甲", chunks.get(0).getText());
        assertEquals("policies 第3行: 名称=乙", chunks.get(1).getText());
    }

    @Test
    void tsvIsDetectedBySniffing() {
        List<Document> chunks = split("等级\t住宿标准\n经理\t500元\n");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().contains("等级=经理 | 住宿标准=500元"),
                "制表符分隔须被嗅探识别: " + chunks.get(0).getText());
    }

    @Test
    void multilineQuotedFieldIsFoldedToOneLine() {
        // RFC4180 允许引号内换行; 折叠为空格以保持"一行一块"
        List<Document> chunks = split("名称,说明\n甲,\"第一行\n第二行\"\n");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().contains("说明=第一行 第二行"),
                "多行字段折叠为单行: " + chunks.get(0).getText());
    }

    @Test
    void headerOnlyTableProducesNoChunks() {
        assertTrue(split("等级,住宿标准\n").isEmpty(),
                "只有表头没有数据行 → 无内容可检索, 交由入库层'解析不出有效文本'兜底");
    }
}
