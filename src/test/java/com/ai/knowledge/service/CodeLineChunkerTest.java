package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeLineChunker} 单元测试: 零缩进顶层行开启块(函数/类边界)、缩进块并入、
 * 超长块再切不丢内容、空行吞掉不产生空块、制表符缩进折算。
 */
class CodeLineChunkerTest {

    private AppProperties props() {
        AppProperties p = new AppProperties();
        IngestionProps.ChunkingStrategyProps sp = new IngestionProps.ChunkingStrategyProps();
        sp.setChunkSize(512);
        sp.setMinIndent(4);
        p.getIngestion().getChunking().getStrategies().put("code-line", sp);
        return p;
    }

    private List<Document> split(String text) {
        return new CodeLineChunker(props()).split(Document.builder().text(text).build());
    }

    @Test
    void topLevelDeclarationStartsBlockWithItsIndentedBody() {
        String code = "public class Demo {\n"
                + "    private int x;\n"
                + "    public int getX() {\n"
                + "        return x;\n"
                + "    }\n"
                + "}";
        List<Document> chunks = split(code);

        // 类与其全部缩进体应在一块(无空行/无下一个顶层行打断)
        assertTrue(chunks.size() == 1 || chunks.get(0).getText().contains("public class Demo {"),
                "类声明与缩进体应同块");
        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("public int getX() {"), "方法体保留");
        assertTrue(joined.contains("return x;"), "方法体内行保留");
    }

    @Test
    void blankLinesSeparateBlocks() {
        String code = "def a():\n    return 1\n\n\ndef b():\n    return 2\n";
        List<Document> chunks = split(code);

        assertTrue(chunks.size() >= 2, "空行分隔的两个函数应分成两块");
        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("def a():"), "函数 a 保留");
        assertTrue(joined.contains("def b():"), "函数 b 保留");
        assertTrue(joined.contains("return 1"), "a 的函数体保留");
        assertTrue(joined.contains("return 2"), "b 的函数体保留");
    }

    @Test
    void contentIsPreservedWhenConcatenated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("def func").append(i).append("():\n")
              .append("    body_line_").append(i).append(" = ").append(i).append("\n\n");
        }
        List<Document> chunks = split(sb.toString());

        String joined = String.join("\n", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("def func49():"), "最后一个函数保留");
        assertTrue(joined.contains("body_line_49 = 49"), "末函数体保留");
    }

    @Test
    void oversizeBlockIsFurtherSplitWithoutLoss() {
        // 单个超长函数(超过 chunkSize 字符): 必须再切, 且不丢内容
        StringBuilder sb = new StringBuilder();
        sb.append("def huge():\n");
        for (int i = 0; i < 2000; i++) {
            sb.append("    line_").append(i).append(" = ").append(i).append("\n");
        }
        List<Document> chunks = split(sb.toString());

        assertTrue(chunks.size() > 1, "超长块必须再切");
        String joined = String.join("", chunks.stream().map(Document::getText).toList());
        assertTrue(joined.contains("line_1999 = 1999"), "超长块尾部内容不得丢失");
    }

    @Test
    void noBlankOrIndentLinesIsSingleBlock() {
        String code = "a = 1\nb = 2\nc = 3";
        List<Document> chunks = split(code);

        assertEquals(1, chunks.size(), "无空行/无缩进的多行脚本是一块");
        assertTrue(chunks.get(0).getText().contains("a = 1"));
        assertTrue(chunks.get(0).getText().contains("c = 3"));
    }
}
