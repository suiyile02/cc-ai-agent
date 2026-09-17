package com.ai.rag.service;
import com.ai.rag.RagMode;

import com.ai.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link KeywordIntentRouter} 单元测试：工具词命中判 TOOL、知识库关键词命中判 KB、
 * 未命中判 GENERAL；两张词表均可通过 app.rag.*-keywords 配置覆盖。
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
    void toolQuestionRoutesToToolAndSkipsKnowledge() {
        KeywordIntentRouter router = new KeywordIntentRouter(new AppProperties());

        // 工具词表命中 → TOOL; 即使"订单/物流"同时出现在知识库词表也按工具处理(工具优先)
        assertEquals(RagMode.TOOL, router.route("查询订单 A123 的物流状态"));
        assertEquals(RagMode.TOOL, router.route("帮我查一下快递到哪了"));
        assertEquals(RagMode.TOOL, router.route("单号 O2025 发货了吗"));
        // 内置同义词扩展(治标缓解"换个说法就漏")
        assertEquals(RagMode.TOOL, router.route("我的包裹到哪了"));
        assertEquals(RagMode.TOOL, router.route("这个订单的物流信息"));
    }

    @Test
    void toolKeywordsAreConfigurable() {
        AppProperties props = new AppProperties();
        props.getRag().setToolKeywords(List.of("天气"));
        KeywordIntentRouter router = new KeywordIntentRouter(props);

        assertEquals(RagMode.TOOL, router.route("今天天气怎么样"));
        assertEquals(RagMode.KB, router.route("查询订单 A123")); // 内置工具词已被覆盖
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
