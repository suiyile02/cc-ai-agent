package com.ai.knowledge.service;

import com.ai.config.AppProperties;
import com.ai.config.props.IngestionProps;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentTextCleaner} 单元测试: 逐规则验证清洗行为与**误杀防护**——
 * 控制字符/不可见残留剔除、U+FFFD 乱码判定、PDF 页眉页脚统计剔除(三重防护)、
 * MD 代码块保护、类型规则矩阵、总开关关闭回归。
 */
class DocumentTextCleanerTest {

    private final AppProperties props = new AppProperties();
    private final DocumentTextCleaner cleaner = new DocumentTextCleaner(props);

    private String clean(String text, String fileType) {
        List<Document> out = cleaner.clean(
                List.of(Document.builder().text(text).build()), fileType);
        return out.get(0).getText();
    }

    /* ---------------- 控制字符与不可见残留 ---------------- */

    @Test
    void controlCharsStrippedButNewlineAndTabKept() {
        String in = "第一行\u0000\u0001第二行\n第三行\u007F\t制表";
        String out = clean(in, "PDF");
        assertFalseContains(out, "\u0000");
        assertFalseContains(out, "\u0001");
        assertFalseContains(out, "\u007F");
        assertTrue(out.contains("第三行\t制表"), "\\t 与 \\n 必须保留");
    }

    @Test
    void nbspNormalizedAndZeroWidthRemoved() {
        String out = clean("宽度\u00A0测试\u200B零宽\uFEFF结尾", "PDF");
        assertEquals("宽度 测试零宽结尾", out);
    }

    /* ---------------- 乱码判定 ---------------- */

    @Test
    void highReplacementDensityFailsAsGarbled() {
        String garbled = "正常开头" + "\uFFFD".repeat(600);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> clean(garbled, "PDF"));
        assertTrue(e.getMessage().contains("疑似乱码"), "必须给出乱码友好标记(供入库层映射)");
    }

    @Test
    void lowReplacementDensityPasses() {
        String sparse = "\uFFFD" + "正常内容。".repeat(200);
        assertEquals(sparse, clean(sparse, "PDF"), "零星替换符不触发乱码判定");
    }

    /* ---------------- PDF 页眉页脚统计剔除 ---------------- */

    /** 构造 n 页文本: 每页含相同页眉/页脚 + 页专属正文 */
    private String pdfWithHeaderFooter(int pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= pages; i++) {
            if (i > 1) {
                sb.append('\f');
            }
            sb.append("机密文档 内部使用\n")
              .append("第 ").append(i).append(" 章正文内容，讨论主题 ").append(i).append(" 的细节。\n")
              .append("内部资料 请勿外传");
        }
        return sb.toString();
    }

    @Test
    void pdfRepeatedHeaderAndFooterRemoved() {
        String out = clean(pdfWithHeaderFooter(5), "PDF");
        assertFalseContains(out, "机密文档 内部使用");
        assertFalseContains(out, "内部资料 请勿外传");
        assertTrue(out.contains("讨论主题 3 的细节"), "正文必须保留");
        assertTrue(out.contains("讨论主题 1 的细节"), "首页正文必须保留");
    }

    @Test
    void tooFewPagesSkipsHeaderFooterStats() {
        String out = clean(pdfWithHeaderFooter(2), "PDF");
        assertTrue(out.contains("机密文档 内部使用"), "页数不足 3 时不做统计剔除(样本不可靠)");
    }

    @Test
    void nonPdfSkipsHeaderFooterRemoval() {
        // 相同重复行在 DOCX 中不剔除(统计法仅对 PDF; DOCX 的重复正文行可能是合法结构)
        String docxLike = "重复行\n正文一\n重复行\n正文二\n重复行\n正文三\n重复行\n正文四";
        String out = clean(docxLike, "DOCX");
        assertTrue(out.contains("重复行"), "非 PDF 不做页眉页脚剔除");
    }

    /* ---------------- 空白规范化 ---------------- */

    @Test
    void blankRunCollapsedButParagraphBreakKept() {
        String out = clean("段一\n\n\n\n\n段二\n\n段三", "PDF");
        assertEquals("段一\n\n段二\n\n段三", out, "2+ 连续空行收敛为 1 空行, 段落分隔保留");
    }

    @Test
    void trailingWhitespaceTrimmedAndInnerSpacesCollapsedForPdf() {
        String out = clean("标题   \n内容   有   多余空格\n", "PDF");
        assertEquals("标题\n内容 有 多余空格", out, "行尾裁剪 + 行中连续空格收敛(保行首缩进)");
    }

    @Test
    void mdCodeBlockIsProtected() {
        String md = "说明如下：\n```java\nint  a   =  1;\n\n\nint  b = 2;\n```\n结尾";
        String out = clean(md, "MD");
        assertTrue(out.contains("int  a   =  1;"), "代码块内空格/空行必须原样保留(语法相关)");
        assertFalseContains(out, "int a = 1;");
    }

    @Test
    void mdTableAlignmentNotCollapsed() {
        String md = "| 列1 | 列2   |\n|-----|------|\n| a   | b    |";
        String out = clean(md, "MD");
        assertEquals(md, out, "MD 的表格对齐空格不得收敛");
    }

    /* ---------------- 开关与边界 ---------------- */

    @Test
    void cleanDisabledReturnsOriginalText() {
        props.getIngestion().getClean().setEnabled(false);
        String dirty = "内容\u0001\u00A0\n\n\n\n\n尾页";
        List<Document> docs = List.of(Document.builder().text(dirty).build());
        List<Document> out = cleaner.clean(docs, "PDF");
        assertEquals(dirty, out.get(0).getText(), "总开关关闭时行为与清洗功能上线前完全一致");
        props.getIngestion().getClean().setEnabled(true);
    }

    @Test
    void documentThatCleansToBlankIsDropped() {
        // 整篇仅控制字符: 清洗后为空 → 文档被丢弃, 由入库层"解析不出有效文本"兜底
        List<Document> docs = List.of(Document.builder().text("\u0001\u0002\u0003").build());
        assertTrue(cleaner.clean(docs, "PDF").isEmpty());
    }

    private static void assertFalseContains(String haystack, String needle) {
        assertFalse(haystack.contains(needle), "不应包含: " + needle.replace("\n", "\\n"));
    }
}
