package com.ai.config;

import com.ai.prompt.PromptService;
import com.ai.rag.ChatOutcome;
import com.ai.session.SessionType;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code app.chat.kb-only=true} 时的端到端绑定与生效验证。
 *
 * <p>用非默认值断言，才能真正证明键名绑到了字段（默认值 false 与"绑定失败留在 false"无法区分）；
 * 并顺带确认 Spring 注入的 {@link PromptService} 在该配置下确实选出严格模板。
 */
@SpringBootTest(properties = "app.chat.kb-only=false")
class KbOnlyEnabledBindingTest {

    @Resource
    private AppProperties appProperties;
    @Resource
    private PromptService promptService;

    @Test
    void propertyOverridesFieldAndSwitchesTemplate() {
        assertFalse(appProperties.getChat().isKbOnly(),
                "默认已是 true, 只有覆盖成 false 还能生效, 才证明键真的绑到了字段");
        assertTrue(promptService.systemFor(SessionType.HYBRID, ChatOutcome.ANSWERED_OPEN, false, null)
                        .contains("常识、科普、闲聊"),
                "关掉开关后 Spring 装配出的 PromptService 应回到自由作答模板");
    }
}
