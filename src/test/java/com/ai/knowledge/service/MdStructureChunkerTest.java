package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import com.ai.knowledge.chunking.ChunkingStrategy;
import com.ai.knowledge.chunking.MdStructureChunker;
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

        // 只在最高层级标题断节: 本例全部归入 # 一级节(次级标题连正文并入)
        assertEquals(1, chunks.size(), "非顶级标题不再独立成节");
        String text = chunks.get(0).getText();
        assertTrue(text.contains("# 第一章 总则\n这是总则内容。"), "标题应与其正文在同一节");
        assertTrue(text.contains("## 1.1 适用范围\n适用于全体员工。"), "二级标题连正文并入同一父块");
        assertTrue(text.contains("### 1.1.1 细则\n细则内容。"), "三级标题连正文并入同一父块");
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

        // 顶级(#/无标题前缀)断节; 次级标题全部并入所在大节——识别≠断节
        assertTrue(chunks.size() >= 1, "至少成节");
        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        for (String h : new String[]{"# h1", "## h2", "### h3", "#### h4"}) {
            assertTrue(joined.contains(h), "标题应保留: " + h);
        }
    }

    /* ---------------- 零正文标题块消除(2026-10-12 检索质量修复) ---------------- */

    /**
     * 线上事故回归: 旧规则让 ## 章标题独立成节 → 光杆标题块既占召回名额又喂不出内容,
     * 且短块在 BM25(B=0.75 长度归一)下分数虚高, "问章节"类查询的 Top-K 被标题刷满。
     * 新规则只在最浅层级标题断节, 任何父块都必须含正文。
     */
    @Test
    void chapterHeadingNeverFormsBareSection() {
        String md = """
                # 《员工手册》示例知识库文档（V1.0）

                ## 第 1 章 考勤与休假制度

                ### 1.1 工作时间
                公司实行标准工时制：周一至周五 9:00-18:00，午休 12:00-13:30。

                ### 1.2 年假规定
                入职满 1 年不满 10 年的员工，每年享有 5 天带薪年假。

                ## 第 2 章 薪酬与报销

                ### 2.1 发薪日
                每月 10 日发放上月工资。
                """;
        List<Document> chunks = split(md);

        // 父块聚合视角: 同 _parent_seq 拼接后不得出现"只有标题"的节
        java.util.Map<Integer, StringBuilder> parents = new java.util.TreeMap<>();
        for (Document c : chunks) {
            int seq = parentSeq(c);
            parents.computeIfAbsent(seq, k -> new StringBuilder()).append(c.getText()).append('\n');
        }
        for (var e : parents.entrySet()) {
            String[] lines = e.getValue().toString().trim().split("\n");
            boolean headingOnly = lines.length == 1 && lines[0].startsWith("#");
            assertTrue(!headingOnly, "父块 " + e.getKey() + " 是零正文标题块: " + lines[0]);
        }
        // 最浅层级(##)断节 → 每章一个父块; 封面 # 行并入第一节
        assertEquals(2, parents.size(), "最浅标题层级是 ## → 两个章父块");
        assertTrue(parents.get(0).toString().contains("### 1.1 工作时间"));
        assertTrue(parents.get(0).toString().contains("公司实行标准工时制"), "章标题连正文同父块");
    }

    /** 无封面引言、以 # 直接开头的文档: # 成为断节层级, ## 仍并入其下。 */
    @Test
    void topLevelIsShallowestHeadingWhenNoPreamble() {
        String md = "# 第 1 章 考勤\n\n## 1.1 工作时间\n朝九晚六。\n\n# 第 2 章 薪酬\n\n## 2.1 发薪\n每月十日。";
        List<Document> chunks = split(md);

        assertEquals(2, chunks.size(), "两个 # 节(## 并入其中)");
        assertTrue(chunks.get(0).getText().contains("## 1.1 工作时间\n朝九晚六。"));
        assertTrue(chunks.get(1).getText().contains("## 2.1 发薪"));
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

        assertEquals(2, chunks.size(), "顶级断节: # 甲(含 ## 乙)与 # 丙 两节");
        assertEquals(List.of(0, 1), chunks.stream().map(MdStructureChunkerTest::parentSeq).toList(),
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
