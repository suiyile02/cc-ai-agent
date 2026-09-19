package com.ai.rag.service;

import com.ai.common.HeuristicTokenCounter;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagContextRenderer} 单元测试：Token 预算内组装、超预算截断、空命中。
 */
class RagContextRendererTest {

    private RagContextRenderer newRenderer() {
        return new RagContextRenderer(new AppProperties(), new HeuristicTokenCounter());
    }

    private Document doc(String file, String text) {
        return Document.builder().text(text).metadata(Map.of("file_name", file)).build();
    }

    @Test
    void buildsContextWithinTokenBudget() {
        String ctx = newRenderer().render(List.of(
                doc("a.md", "年假制度内容一二三四五"),
                doc("b.md", "报销标准内容六七八九十")), 10000);

        assertTrue(ctx.contains("a.md"));
        assertTrue(ctx.contains("b.md"));
        assertTrue(ctx.startsWith("===== 知识库资料开始"), "资料区必须有注入防护的起止标记");
    }

    @Test
    void truncatesWhenOverTokenBudget() {
        String ctx = newRenderer().render(List.of(
                doc("a.md", "年假制度".repeat(50)),
                doc("b.md", "报销标准".repeat(50))), 20);

        assertTrue(ctx.contains("Token 预算截断"));
        assertFalse(ctx.contains("b.md"), "超预算后不应再纳入后续文档");
    }

    @Test
    void emptyHitsYieldEmptyContext() {
        assertTrue(newRenderer().render(List.of(), 100).isEmpty());
    }
}
