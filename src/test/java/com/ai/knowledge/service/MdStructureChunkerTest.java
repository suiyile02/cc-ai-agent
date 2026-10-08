package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MdStructureChunker} 单元测试: 按标题切节、标题保留在节首(检索语境)、
 * 超长节标点回退再切、无标题文本退化为单块、内容不丢(拼接还原)、4 级标题均识别。
 */
class MdStructureChunkerTest {

    private AppProperties props() {
        AppProperties p = new AppProperties();
        IngestionProps.ChunkingStrategyProps sp = new IngestionProps.ChunkingStrategyProps();
        sp.setChunkSize(512);
        p.getIngestion().getChunking().getStrategies().put("md-structure", sp);
        return p;
    }

    private List<Document> split(String text) {
        return new MdStructureChunker(props()).split(Document.builder().text(text).build());
    }

    @Test
    void splitsByHeadingLevels() {
        String md = "# 第一章 总则\n这是总则内容。\n\n## 1.1 适用范围\n适用于全体员工。\n\n### 1.1.1 细则\n细则内容。";
        List<Document> chunks = split(md);

        assertTrue(chunks.size() >= 3, "按标题应切出至少 3 节");
        // 每个标题行保留在所属节首(语境携带)
        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("# 第一章 总则\n这是总则内容。"), "标题应与其正文在同一节");
        assertTrue(joined.contains("## 1.1 适用范围\n适用于全体员工。"), "二级标题节");
        assertTrue(joined.contains("### 1.1.1 细则\n细则内容。"), "三级标题节");
    }

    @Test
    void contentIsPreservedWhenConcatenated() {
        String md = "# 甲\n甲正文1。\n\n## 乙\n乙正文1。\n乙正文2。\n\n# 丙\n丙正文。";
        List<Document> chunks = split(md);

        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        for (String token : new String[]{"甲正文1。", "乙正文1。", "乙正文2。", "丙正文。"}) {
            assertTrue(joined.contains(token), "内容不得丢失: " + token);
        }
    }

    @Test
    void noHeadingTextFallsBackToWholeSection() {
        String plain = "没有任何标题的普通文本段落。第二句。第三句。";
        List<Document> chunks = split(plain);

        assertEquals(1, chunks.size(), "无标题文本退化为单块");
        assertTrue(chunks.get(0).getText().contains("没有任何标题"));
    }

    @Test
    void oversizeSectionIsFurtherSplitWithoutLoss() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 大节\n");
        for (int i = 0; i < 500; i++) {
            sb.append("这是第").append(i).append("句,内容足够长使本节能被再次切分。");
        }
        List<Document> chunks = split(sb.toString());

        assertTrue(chunks.size() > 1, "超长节必须被进一步切分");
        String joined = String.join("", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("这是第499句"), "超长节内容不得丢失");
    }

    @Test
    void recognizesHeadingsOneToFourLevels() {
        String md = "# h1\n内容1。\n## h2\n内容2。\n### h3\n内容3。\n#### h4\n内容4。";
        List<Document> chunks = split(md);

        assertTrue(chunks.size() >= 4, "1~4 级标题都应被识别为节边界");
        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        for (String h : new String[]{"# h1", "## h2", "### h3", "#### h4"}) {
            assertTrue(joined.contains(h), "标题应保留: " + h);
        }
    }

    /* ---------------- 父子文档(小-大检索): 小节序号 = 父块序号 ---------------- */

    private static int parentSeq(Document d) {
        Object seq = d.getMetadata().get(ChunkingStrategy.PARENT_SEQ_KEY);
        assertTrue(seq instanceof Number, "每个小块必须携带 " + ChunkingStrategy.PARENT_SEQ_KEY);
        return ((Number) seq).intValue();
    }

    @Test
    void eachSectionCarriesItsOwnParentSeq() {
        String md = "# 甲\n甲正文。\n\n## 乙\n乙正文。\n\n# 丙\n丙正文。";
        List<Document> chunks = split(md);

        assertEquals(3, chunks.size(), "三个短节各成一块");
        assertEquals(List.of(0, 1, 2), chunks.stream().map(MdStructureChunkerTest::parentSeq).toList(),
                "父块序号按节出现顺序编号(入库层据此拼父块)");
    }

    @Test
    void oversizeSectionPiecesShareOneParentSeq() {
        // 一个超长节被切成多块: 召回应按小块多路命中, 注入时必须收敛回同一个父块
        StringBuilder sb = new StringBuilder();
        sb.append("# 大节\n");
        for (int i = 0; i < 500; i++) {
            sb.append("这是第").append(i).append("句,内容足够长使本节能被再次切分。");
        }
        List<Document> chunks = split(sb.toString());

        assertTrue(chunks.size() > 1, "前置确认: 超长节确实被再切");
        assertEquals(java.util.Set.of(0),
                chunks.stream().map(MdStructureChunkerTest::parentSeq).collect(java.util.stream.Collectors.toSet()),
                "同节所有小块必须指向同一父块序号");
    }
}
