package com.ai.context.service;

import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.prompt.PromptService;
import com.ai.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShortQueryExpander} 单元测试：触发条件、降级回退、输出清洗，以及"扩展结果等同原问题"视为未扩展。
 *
 * <p>模型调用经 {@link ShortQueryExpander#complete(String)} 这个测试替换点注入, 不触真实端点。
 */
class ShortQueryExpanderTest {

    private AppProperties appProperties;
    private ChatClientProvider provider;
    private PromptService promptService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        provider = mock(ChatClientProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(mock(ChatClient.class));
        promptService = mock(PromptService.class);
        lenient().when(promptService.template("prompts/short-query-expand.st")).thenReturn("Q:{{query}}");
    }

    /** 用给定"模型输出"构造被测器 */
    private ShortQueryExpander withModel(Function<String, String> completion) {
        return new ShortQueryExpander(appProperties, provider, promptService) {
            @Override
            String complete(String prompt) {
                return completion.apply(prompt);
            }
        };
    }

    private ShortQueryExpander expanding(String output) {
        return withModel(p -> output);
    }

    @Test
    void expandsWhenQueryShorterThanThreshold() {
        ShortQueryExpander.ExpandResult r = expanding("产品的定价和套餐有哪些？")
                .expand(SessionType.HYBRID, "产品");
        assertTrue(r.expanded());
        assertEquals("产品的定价和套餐有哪些？", r.query());
    }

    @Test
    void skipsCompleteSentences() {
        ShortQueryExpander.ExpandResult r = expanding("不该被调用")
                .expand(SessionType.HYBRID, "你们产品的定价和套餐分别是什么");
        assertFalse(r.expanded(), "长度已达 min-chars 的问题零等待直检");
        assertEquals("你们产品的定价和套餐分别是什么", r.query());
    }

    @Test
    void skipsAgentSessionsAndDisabledSwitch() {
        assertFalse(expanding("x").expand(SessionType.AGENT, "产品").expanded(),
                "AGENT 会话靠工具作答, 不为它多花一次调用");
        appProperties.getContext().getShortQuery().setEnabled(false);
        assertFalse(expanding("x").expand(SessionType.RAG, "产品").expanded());
    }

    @Test
    void skipsWhenModelUnavailable() {
        when(provider.getIfAvailable()).thenReturn(null);
        ShortQueryExpander.ExpandResult r = expanding("x").expand(SessionType.RAG, "产品");
        assertFalse(r.expanded(), "模型未配置时直接回退原问题, 不等待不报错");
        assertEquals("产品", r.query());
    }

    @Test
    void fallsBackOnModelFailure() {
        ShortQueryExpander.ExpandResult r = withModel(p -> {
            throw new IllegalStateException("Connection reset");
        }).expand(SessionType.RAG, "产品");
        assertFalse(r.expanded());
        assertEquals("产品", r.query(), "扩展失败绝不影响对话");
    }

    @Test
    void treatsEmptyOrSameOutputAsNotExpanded() {
        assertFalse(expanding("   ").expand(SessionType.RAG, "产品").expanded());
        assertFalse(expanding("产品").expand(SessionType.RAG, " 产品 ").expanded(),
                "模型原样返回等同未扩展, 不能标记为已改写");
    }

    @Test
    void sanitizesQuotesPrefixMultiLineAndLength() {
        assertEquals("产品有哪些套餐",
                expanding("“产品有哪些套餐”").expand(SessionType.RAG, "产品").query());
        assertEquals("产品有哪些套餐",
                expanding("补全后：产品有哪些套餐").expand(SessionType.RAG, "产品").query());
        assertEquals("第一行",
                expanding("第一行\n第二行解释文字").expand(SessionType.RAG, "产品").query());
        assertEquals("一".repeat(60),
                expanding("一".repeat(200)).expand(SessionType.RAG, "产品").query(),
                "模型啰嗦时按 max-chars 硬截断");
    }

    @Test
    void whitespaceOnlyQueryIsSkipped() {
        assertFalse(expanding("x").expand(SessionType.RAG, "  ").expanded());
    }
}
