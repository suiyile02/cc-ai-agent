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
 * {@code app.chat.kb-only} 的<b>覆盖路径</b>验证(键名 → 字段 → 实际生效)。
 *
 * <p>用非默认值 false 覆盖才能证明"这个键真的还能绑到字段上、并且能改变行为"——默认值已是 true,
 * 只断言 true 无法区分"绑定成功"与"绑定失败停在别处"。它同时是严格绑定开关的回归哨兵: 若将来有人
 * 把 kb-only 改名而忘了改 yaml, {@code AppPropertiesBindingGuardTest} 会在启动期就报出来, 本测试则
 * 会从"覆盖不再生效"的角度再报一次。仓库默认值本身由 {@link KbOnlyDefaultBindingTest} 守。
 */
@SpringBootTest(properties = "app.chat.kb-only=false")
class KbOnlyOverrideBindingTest {

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
