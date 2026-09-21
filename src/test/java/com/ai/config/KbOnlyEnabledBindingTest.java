package com.ai.config;

import com.ai.prompt.PromptService;
import com.ai.rag.RagMode;
import com.ai.session.SessionType;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code app.chat.kb-only=true} 时的端到端绑定与生效验证。
 *
 * <p>用非默认值断言，才能真正证明键名绑到了字段（默认值 false 与"绑定失败留在 false"无法区分）；
 * 并顺带确认 Spring 注入的 {@link PromptService} 在该配置下确实选出严格模板。
 */
@SpringBootTest(properties = "app.chat.kb-only=true")
class KbOnlyEnabledBindingTest {

    @Resource
    private AppProperties appProperties;
    @Resource
    private PromptService promptService;

    @Test
    void propertyOverridesFieldAndSwitchesTemplate() {
        assertTrue(appProperties.getChat().isKbOnly(), "app.chat.kb-only=true 必须绑到 Chat.kbOnly");
        assertTrue(promptService.systemFor(SessionType.HYBRID, RagMode.GENERAL, false, null)
                        .contains("只能依据"),
                "开关打开后 Spring 装配出的 PromptService 应选中严格模板");
    }
}
