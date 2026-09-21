package com.ai.config;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code app.chat.kb-only} 的实际绑定测试：读真实 application.yaml。
 *
 * <p>提示词分支逻辑由 {@code PromptServiceTest} 覆盖, 但那里是直接给字段赋值构造的, 没有覆盖
 * "yaml 里的键名能否绑到字段"。键名拼错不会报错, 开关会静默停在 false, 故实测两件事：
 * ① 该键确实存在于 yaml(拼写正确)；② 其值按预期解析。
 */
@SpringBootTest
class KbOnlyDefaultBindingTest {

    @Resource
    private AppProperties appProperties;
    @Resource
    private Environment environment;

    @Test
    void yamlDefinesTheKebabCaseKeyAndItBindsToDisabled() {
        assertTrue(environment.containsProperty("app.chat.kb-only"),
                "application.yaml 里必须存在 app.chat.kb-only 这个键(键名拼错时 Spring 静默忽略, 开关形同虚设)");
        assertFalse(appProperties.getChat().isKbOnly(),
                "默认应为关闭: 严格模式改变答题口径, 不得在未显式开启时影响既有行为");
    }
}
