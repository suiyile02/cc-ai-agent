package com.ai.common;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JtokTokenCounter} 单元测试：精确计数、消息开销口径、精确截断与边界处理。
 */
class JtokTokenCounterTest {

    private final JtokTokenCounter counter = new JtokTokenCounter();

    @Test
    void countsNullAndEmptyAsZero() {
        assertEquals(0, counter.count((String) null));
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count((List<Message>) null));
        assertEquals(0, counter.count(List.of()));
    }

    @Test
    void countsEnglishTextPrecisely() {
        // CL100K 对常见英文单词约 1 词元
        assertEquals(1, counter.count("hello"));
        assertTrue(counter.count("hello world") >= 2);
    }

    @Test
    void countsChineseText() {
        int tokens = counter.count("年假制度");
        // 中文常见字约 1~2 词元/字, 仅要求为正且不超过字数*2
        assertTrue(tokens >= 4 && tokens <= 8, "中文计数异常: " + tokens);
    }

    @Test
    void countIsDeterministicAndMonotonic() {
        String shortText = "年假";
        String longText = "年假制度规定入职满两年的员工享有十天带薪年假";
        assertEquals(counter.count(longText), counter.count(longText));
        assertTrue(counter.count(longText) > counter.count(shortText));
    }

    @Test
    void messageListAddsPerMessageOverhead() {
        List<Message> messages = List.of(new UserMessage("hello"), new AssistantMessage("hi"));
        int expected = counter.count("hello") + counter.count("hi") + 4 * 2;

        assertEquals(expected, counter.count(messages));
    }

    @Test
    void messageListSkipsNullElements() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("hello"));
        messages.add(null);

        assertEquals(counter.count("hello") + 4, counter.count(messages));
    }

    @Test
    void truncateReturnsOriginalWhenWithinBudget() {
        String text = "入职满两年享有 10 天年假";
        int tokens = counter.count(text);

        assertEquals(text, counter.truncateToTokens(text, tokens));
        assertEquals(text, counter.truncateToTokens(text, tokens + 100));
    }

    @Test
    void truncateIsPreciseWithinBudget() {
        String text = "入职满两年的员工每年享有十天带薪年假，需提前三个工作日提交申请。";
        int maxTokens = 8;

        String cut = counter.truncateToTokens(text, maxTokens);

        assertTrue(cut.length() < text.length(), "应发生截断");
        assertTrue(counter.count(cut) <= maxTokens,
                "截断后 token 数 " + counter.count(cut) + " 应 ≤ " + maxTokens);
    }

    @Test
    void truncateHandlesEdgeCases() {
        assertNull(counter.truncateToTokens(null, 10));
        assertEquals("", counter.truncateToTokens("任意文本", 0));
        assertEquals("", counter.truncateToTokens("", 10));
    }

    @Test
    void truncateDecodesToReadableText() {
        String text = "hello world 年假制度";
        String cut = counter.truncateToTokens(text, 2);

        // 解码结果应为原文前缀(可读), 而非乱码
        assertTrue(text.startsWith(cut), "截断结果应是原文前缀: [" + cut + "]");
    }
}
