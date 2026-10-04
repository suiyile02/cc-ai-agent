package com.ai.context.service;

import com.ai.common.JtokTokenCounter;
import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConversationSummarizer} 结构化摘要规范化单元测试：四节节头校验、固定节序重组、
 * 逐节截断(预算均分)——格式不合格必须返回 null(由调用方保留旧摘要), 不允许残缺摘要进入上下文。
 */
class ConversationSummarizerTest {

    private final ConversationSummarizer summarizer =
            new ConversationSummarizer(null, null, new JtokTokenCounter(), new AppProperties());

    @Test
    void wellFormedSummaryIsReassembledInCanonicalOrder() {
        // 节序故意打乱: 规范化必须按固定节序重组
        String raw = """
                【未决事项】加班费口径待确认
                【会话意图】了解年假与调休制度
                【口径约束】用中文简洁回答
                【已确认事实】年假按入职年限计算; 调休 1:1
                """;

        String out = summarizer.normalizeSections(raw, 400);

        assertTrue(out.startsWith("【会话意图】"), "必须以第一节开头");
        assertTrue(out.indexOf("【会话意图】") < out.indexOf("【已确认事实】"));
        assertTrue(out.indexOf("【已确认事实】") < out.indexOf("【未决事项】"));
        assertTrue(out.indexOf("【未决事项】") < out.indexOf("【口径约束】"));
        assertTrue(out.contains("年假按入职年限计算"), "节内容必须保留");
    }

    @Test
    void missingSectionHeaderIsRejected() {
        String raw = """
                【会话意图】了解年假制度
                【已确认事实】年假按入职年限计算
                """;

        assertNull(summarizer.normalizeSections(raw, 400), "缺节头的输出不得进入上下文");
        assertNull(summarizer.normalizeSections("   ", 400));
        assertNull(summarizer.normalizeSections(null, 400));
    }

    @Test
    void oversizedSectionsAreTruncatedPerSection() {
        String fat = "好".repeat(2000);
        String raw = "【会话意图】" + fat
                + "\n【已确认事实】事实一"
                + "\n【未决事项】无"
                + "\n【口径约束】无";

        String out = summarizer.normalizeSections(raw, 400);

        assertNotNull(out);
        for (String header : ConversationSummarizer.SECTION_HEADERS) {
            assertTrue(out.contains(header), "逐节截断后四节仍须齐全: " + header);
        }
        // 每节预算 400/4=100 token: 被截断的意图节不得再携带 2000 字
        int intentEnd = out.indexOf("【已确认事实】");
        assertTrue(intentEnd < 600, "单节必须被截断到预算附近, 而非整段保留");
        // 其余小节内容不因第一节超长而消失
        assertTrue(out.contains("事实一"));
        assertTrue(out.endsWith("无") || out.contains("【口径约束】无"));
        assertFalse(out.contains("好".repeat(1500)), "截断必须生效");
    }
}
