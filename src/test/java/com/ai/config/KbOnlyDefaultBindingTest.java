package com.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code app.chat.kb-only} 的"仓库默认值"守卫。
 *
 * <p>刻意<b>不</b>用 @SpringBootTest：合并后的 Environment 会把 gitignore 的
 * {@code application-local.yaml}(本机偏好)算进来, 那样一个纯本地的开关会让测试无端变红。
 * 本守卫要管的是"提交进仓库的默认行为", 所以直接读 classpath 上的 application.yaml 原文。
 *
 * <p>为什么仍要断言 yaml 里<b>存在</b>该键(而不是只信 Java 默认值): 严格知识库是本项目最重要的
 * 行为闸门, "仓库里看得见它开着"本身就是上线前检查清单的一项——它属于配置策略里的"安全基线"类,
 * 与 {@code AppPropertiesBindingGuardTest} 的安全基线断言配对: 那里守 Java 侧, 这里守 yaml 侧。
 * 覆盖路径(改成 false 还能不能生效)见 {@link KbOnlyOverrideBindingTest}。
 */
class KbOnlyDefaultBindingTest {

    /** 只允许出现一次该键, 且取值必须是 true */
    private static final Pattern KB_ONLY = Pattern.compile("^\\s*kb-only:\\s*(\\w+)\\s*(?:#.*)?$",
            Pattern.MULTILINE);

    @Test
    void yamlInRepoDeclaresTheKeyExactlyOnceAsEnabled() throws IOException {
        String yaml = readClasspath("application.yaml");

        Matcher m = KB_ONLY.matcher(yaml);
        assertTrue(m.find(), "application.yaml 必须显式声明 app.chat.kb-only(安全基线项要在仓库配置里看得见)");
        assertEquals("true", m.group(1), "提交进仓库的默认必须是开启(P3-7): 无据可依时友好拒答");
        assertFalse(m.find(), "该键只允许声明一次, 出现两处则有一份永远不生效(单一事实来源)");
    }

    @Test
    void javaDefaultMatchesYaml() {
        // 作用于 new AppProperties()(单元测试的构造路径), 与 yaml 无关; 必须与 yaml 同值, 否则两处漂移
        assertTrue(new AppProperties().getChat().isKbOnly(),
                "Java 默认值必须与 application.yaml 一致(两处不一致时读代码的人会被误导)");
    }

    private static String readClasspath(String name) throws IOException {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
