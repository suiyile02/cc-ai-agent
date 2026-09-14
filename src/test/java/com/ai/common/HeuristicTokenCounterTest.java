package com.ai.common;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeuristicTokenCounter} 单元测试：CJK/拉丁计数、消息开销、按预算截断。
 */
class HeuristicTokenCounterTest {

    private final HeuristicTokenCounter counter = new HeuristicTokenCounter();

    @Test
    void countsCjkAsOneTokenEach() {
        assertEquals(4, counter.count("年假制度"));
    }

    @Test
    void countsLatinByQuarterLength() {
        assertEquals(2, counter.count("abcdefg")); // ceil(7/4)=2
    }

    @Test
    void countsEmptyAndNullAsZero() {
        assertEquals(0, counter.count(""));
        assertEquals(0, counter.count((String) null));
        assertEquals(0, counter.count((List<Message>) null));
    }

    @Test
    void messagesIncludePerMessageOverhead() {
        List<Message> msgs = List.of(new UserMessage("年假"));
        assertEquals(2 + 4, counter.count(msgs)); // 2 CJK + 4 开销
    }

    @Test
    void truncateKeepsWithinBudget() {
        String text = "年假制度".repeat(50); // 200 tokens
        String cut = counter.truncateToTokens(text, 10);
        assertFalse(cut.isEmpty());
        assertTrue(counter.count(cut) <= 10);
    }

    @Test
    void truncateReturnsOriginalWhenWithinBudget() {
        assertEquals("年假制度", counter.truncateToTokens("年假制度", 100));
    }
}
