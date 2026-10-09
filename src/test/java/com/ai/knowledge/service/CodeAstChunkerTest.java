package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import com.ai.knowledge.chunking.ChunkingStrategy;
import com.ai.knowledge.chunking.CodeAstChunker;
import com.ai.knowledge.chunking.CodeLineChunker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeAstChunker} 单元测试(真实 tree-sitter 语法包 + 真实降级通道):
 * 声明边界切分、超长声明拆子声明与父块、独立声明无父块、紧贴注释归位、无语法包/异常降级。
 */
class CodeAstChunkerTest {

    private static final String JAVA_CLASS = "public class Demo {\n"
            + "    private int x;\n"
            + "    public int getX() {\n"
            + "        return x;\n"
            + "    }\n"
            + "    public void setX(int v) {\n"
            + "        this.x = v;\n"
            + "    }\n"
            + "    public void reset() {\n"
            + "        this.x = 0;\n"
            + "    }\n"
            + "    public String describe() {\n"
            + "        return \"x=\" + x;\n"
            + "    }\n"
            + "}\n";

    private AppProperties props(int chunkSize, int parentMaxChars) {
        AppProperties p = new AppProperties();
        IngestionProps.ChunkingStrategyProps ast = new IngestionProps.ChunkingStrategyProps();
        ast.setChunkSize(chunkSize);
        ast.setParentMaxChars(parentMaxChars);
        p.getIngestion().getChunking().getStrategies().put("code-ast", ast);
        IngestionProps.ChunkingStrategyProps line = new IngestionProps.ChunkingStrategyProps();
        line.setChunkSize(chunkSize);
        line.setMinIndent(4);
        p.getIngestion().getChunking().getStrategies().put("code-line", line);
        return p;
    }

    private CodeAstChunker chunker(int chunkSize, int parentMaxChars) {
        AppProperties p = props(chunkSize, parentMaxChars);
        return new CodeAstChunker(p, new CodeLineChunker(p));
    }

    private List<Document> split(String text, String source, int chunkSize, int parentMaxChars) {
        return chunker(chunkSize, parentMaxChars).split(Document.builder()
                .text(text)
                .metadata(new HashMap<>(Map.of("source", source)))
                .build());
    }

    private static String joined(List<Document> chunks) {
        return chunks.stream().map(Document::getText).collect(Collectors.joining("\n"));
    }

    private static Integer seq(Document d) {
        Object v = d.getMetadata().get(ChunkingStrategy.PARENT_SEQ_KEY);
        return v instanceof Number n ? n.intValue() : null;
    }

    @Test
    void javaClassSplitsByDeclarationAndChildrenShareClassParent() {
        // chunkSize=200 < 类文本: 类被拆到字段/方法; 类文本 3000 以内 → 全部子块共享父块=类
        List<Document> chunks = split(JAVA_CLASS, "Demo.java", 200, 3000);

        assertTrue(chunks.size() >= 4, "字段/方法应各自成块: " + chunks.size());
        assertFalse(joined(chunks).contains("public class Demo"),
                "超长类不应整体成块(否则检索粒度太粗)");
        assertTrue(joined(chunks).contains("public int getX()"), "方法声明成块");
        assertTrue(joined(chunks).contains("public String describe()"), "方法声明成块");
        for (Document c : chunks) {
            assertEquals(0, seq(c), "类 ≤ parent-max-chars 时子块共享父块=类(命中方法注入整类)");
        }
    }

    @Test
    void oversizeMethodPiecesShareOwnParentWhileSmallDeclsHaveNoParent() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("        // 第").append(i).append("行说明,足够长以让本方法超过块上限。\n");
        }
        String java = "public class Big {\n"
                + "    public void small() {\n        int a = 1;\n    }\n"
                + "    public void huge() {\n" + body + "    }\n"
                + "}\n";
        // parentMaxChars=150 < 类文本: 类不做统一父块; huge 方法超 chunkSize → 标点回退, 各片共享自身父块
        List<Document> chunks = split(java, "Big.java", 300, 150);

        List<Document> small = chunks.stream().filter(c -> c.getText().contains("public void small()")).toList();
        assertEquals(1, small.size(), "小方法整块成块");
        assertEquals(null, seq(small.get(0)), "独立(未被拆的)声明不设父块: 展开=自身无意义");

        List<Integer> hugeSeqs = chunks.stream()
                .filter(c -> !c.getText().contains("public void small()"))
                .map(CodeAstChunkerTest::seq).toList();
        assertTrue(hugeSeqs.size() > 1, "超长方法应拆成多片");
        assertTrue(hugeSeqs.stream().allMatch(s -> s != null && s == hugeSeqs.get(0)),
                "同一超长方法的各片共享一个父块序号: " + hugeSeqs);
    }

    @Test
    void pythonFunctionsSplitByDeclarationAndSmallOnesHaveNoParent() {
        String py = "def a():\n    return 1\n\n\ndef b():\n    return 2\n";
        List<Document> chunks = split(py, "t.py", 512, 3000);

        assertEquals(2, chunks.size(), "两个函数各自成块(空行/缩进不再参与边界判定)");
        assertTrue(chunks.get(0).getText().startsWith("def a():"));
        assertTrue(chunks.get(1).getText().startsWith("def b():"));
        assertEquals(null, seq(chunks.get(0)), "独立小函数无父块");
        assertEquals(null, seq(chunks.get(1)));
    }

    @Test
    void adjacentLeadingCommentIsAttachedButBlankLineSeparatedIsNot() {
        String py = "# 文档注释\n"
                + "def a():\n    return 1\n"
                + "\n"
                + "# 孤立的注释\n"
                + "\n"
                + "def b():\n    return 2\n";
        List<Document> chunks = split(py, "t.py", 512, 3000);

        String a = chunks.stream().filter(c -> c.getText().contains("def a()")).findFirst().orElseThrow().getText();
        String b = chunks.stream().filter(c -> c.getText().contains("def b()")).findFirst().orElseThrow().getText();
        assertTrue(a.contains("# 文档注释"), "紧贴声明上方的注释应随块切分(文档注释归其描述对象): " + a);
        assertFalse(b.contains("# 孤立的注释"), "空行隔开的注释不属于下方声明");
    }

    @Test
    void unknownExtensionDegradesToCodeLine() {
        String code = "def a():\n    return 1\n\n\ndef b():\n    return 2\n";
        List<Document> ast = split(code, "script.rb", 512, 3000);
        List<Document> line = new CodeLineChunker(props(512, 3000)).split(Document.builder()
                .text(code).metadata(new HashMap<>(Map.of("source", "script.rb"))).build());

        assertEquals(line.stream().map(Document::getText).toList(),
                ast.stream().map(Document::getText).toList(),
                "无语法包的扩展名必须与升级前(code-line)行为完全一致");
        assertEquals(line.stream().map(CodeAstChunkerTest::seq).toList(),
                ast.stream().map(CodeAstChunkerTest::seq).toList(),
                "降级时父块标记口径也一致(走 code-line 的缩进块序号)");
    }

    @Test
    void goPackageClauseIsSkipped() {
        String go = "package main\n\nfunc main() {\n\tprintln(1)\n}\n";
        List<Document> chunks = split(go, "main.go", 512, 3000);

        assertEquals(1, chunks.size(), "package 声明无检索价值, 不单独成块");
        assertTrue(chunks.get(0).getText().contains("func main()"));
    }

    @Test
    void contentIsPreservedAcrossChunks() {
        List<Document> chunks = split(JAVA_CLASS, "Demo.java", 200, 3000);

        String all = joined(chunks);
        for (String token : new String[]{"private int x;", "return x;", "this.x = v;", "this.x = 0;",
                "return \"x=\" + x;"}) {
            assertTrue(all.contains(token), "内容不得丢失: " + token);
        }
    }

    @Test
    void brokenCodeDoesNotCrashAndKeepsContent() {
        String broken = "class {\n  void m() {}\n}\n";
        List<Document> chunks = split(broken, "Broken.java", 512, 3000);

        assertNotNull(chunks);
        assertTrue(joined(chunks).contains("void m()"), "损坏代码也不得丢内容: " + joined(chunks));
    }

    @Test
    void commentOnlyFileFallsBackInsteadOfProducingNothing() {
        String onlyComment = "// 只有注释没有声明\n// 第二行注释\n";
        List<Document> chunks = split(onlyComment, "Notes.java", 512, 3000);

        assertFalse(chunks.isEmpty(), "纯注释文件不得产出空分块(否则入库误判'解析不出有效文本')");
        assertTrue(joined(chunks).contains("只有注释没有声明"));
    }

    @Test
    void nameAndParentContract() {
        CodeAstChunker c = chunker(512, 3000);
        assertEquals("code-ast", c.name());
        assertTrue(c.producesParents());
        assertFalse(c.everyChunkHasParent(), "允许独立声明无父块(与 md/table/code-line 的'每块必有父块'不同)");
    }

    @Test
    void javascriptAndTypescriptGrammarsAreWired() {
        // AST 路径生效的判别标志: 独立小函数无父块键——若走了降级通道(code-line),
        // 每块都会被赋予缩进块序号(非 null)
        List<Document> js = split("function a(x) { return x; }\n\nfunction b(y) { return y; }\n",
                "t.js", 512, 3000);
        assertEquals(2, js.size());
        assertEquals(null, seq(js.get(0)), "JS 语法包未生效(疑似降级为 code-line)");

        List<Document> ts = split("function f(a: number): number { return a; }\n\nfunction g(): void {}\n",
                "t.ts", 512, 3000);
        assertEquals(2, ts.size());
        assertEquals(null, seq(ts.get(0)), "TS 语法包未生效(疑似降级为 code-line)");
    }
}
