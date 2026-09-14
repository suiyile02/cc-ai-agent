package com.ai.rag.service;
import com.ai.rag.RagMode;

import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link KeywordIntentRouter} 单元测试：关键词命中判 KB、未命中判 GENERAL、
 * 关键词表可通过 app.rag.internal-keywords 配置覆盖。
 */
class KeywordIntentRouterTest {

    @Test
    void internalKeywordRoutesToKb() {
        AppProperties props = new AppProperties();
        KeywordIntentRouter router = new KeywordIntentRouter(props);

        assertEquals(RagMode.KB, router.route("入职满两年能休几天年假？"));
        assertEquals(RagMode.KB, router.route("报销住宿标准是多少？"));
    }

    @Test
    void generalQuestionRoutesToGeneral() {
        KeywordIntentRouter router = new KeywordIntentRouter(new AppProperties());

        assertEquals(RagMode.GENERAL, router.route("地球为什么是圆的？"));
        assertEquals(RagMode.GENERAL, router.route(""));
        assertEquals(RagMode.GENERAL, router.route(null));
    }

    @Test
    void keywordsAreConfigurable() {
        AppProperties props = new AppProperties();
        props.getRag().setInternalKeywords(List.of("机密"));
        KeywordIntentRouter router = new KeywordIntentRouter(props);

        assertEquals(RagMode.KB, router.route("这是机密文件吗"));
        assertEquals(RagMode.GENERAL, router.route("年假有几天")); // 内置词表已被覆盖
    }
}
