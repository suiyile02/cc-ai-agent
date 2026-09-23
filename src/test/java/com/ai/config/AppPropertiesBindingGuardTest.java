package com.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置漂移守卫(三条断言各挡一类真实踩过的坑)。
 *
 * <ol>
 *   <li><b>仓库内每个 {@code app.*} 键都必须真的被消费</b>——逐个向 Spring 注入的 {@link AppProperties}
 *       bean 问"你有这个字段吗"。配合 {@code @ConfigurationProperties(ignoreUnknownFields = false)}
 *       (Boot 4 默认为 true, 会<b>静默忽略</b>绑不上的键), "改了配置没生效""键已随重构删除却还留在
 *       yaml 里""键名拼错"三类问题都会在启动期或本测试里暴露。</li>
 *   <li><b>豁免清单必须仍然有用</b>——{@link AppProperties#EXEMPT_UNKNOWN_KEYS} 是给"装配期开关"
 *       (无字段可绑)开的口子; 清单里的键若已不在配置中, 说明它该跟着字段一起删, 留着就是假旋钮。</li>
 *   <li><b>Qdrant 集合名只允许一处取值</b> + <b>安全基线默认值不得被悄悄改松</b>。</li>
 * </ol>
 *
 * <p><b>为什么不能"造一个新 AppProperties 单独绑一个键"来判定绑定</b>: 实测那样恒为真——Spring 的
 * 类型转换会把任意字符串喂给任何字段({@code "x"} → boolean {@code false}、→ int {@code 0}),
 * 未绑定异常又只在顶层绑定收尾时抛出。所以必须问真实 bean。
 *
 * <p>用 {@link SpringBootTest} 而非手读 yaml: 只有走真实的属性合并, 才证明"线上生效的那份配置"可绑定。
 * gitignore 的 {@code application-local.yaml} 只提供 {@code DB_PASSWORD} 这类顶层占位符名, 不在
 * {@code app.} 前缀下, 不会污染断言。
 */
@SpringBootTest
class AppPropertiesBindingGuardTest {

    @Autowired
    private AppProperties appProperties;
    @Autowired
    private ConfigurableEnvironment environment;

    /** 断言 1 + 2：app.* 键与"字段 / 豁免清单"一一对应，不多不少。 */
    @Test
    void everyAppKeyIsConsumedAndExemptionListIsStillNeeded() throws Exception {
        Set<String> declared = new HashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith("app.") && enumerable.getName().contains("application.yaml")) {
                    declared.add(name);
                }
            }
        }
        assertTrue(declared.size() >= 15,
                "预期枚举到十余个 app.* 键, 实测 " + declared.size() + " 个: yaml 读取路径变了?");

        List<String> orphan = new ArrayList<>();
        for (String key : declared) {
            if (AppProperties.EXEMPT_UNKNOWN_KEYS.contains(key)) {
                continue;
            }
            // 取不到 getter 即视为"没有对应字段"(NoSuchMethodException 走这里, 不必单独分类)
            if (!boundToField(key)) {
                orphan.add(key);
            }
        }
        assertTrue(orphan.isEmpty(),
                "以下 app.* 键既没有对应字段也不在 EXEMPT_UNKNOWN_KEYS 里(拼错/已删除/重命名后忘同步):\n  "
                        + String.join("\n  ", orphan));

        List<String> staleExemptions = AppProperties.EXEMPT_UNKNOWN_KEYS.stream()
                .filter(k -> !declared.contains(k))
                .toList();
        assertTrue(staleExemptions.isEmpty(),
                "豁免清单里有键已经不在配置中了, 它该随字段一起删掉, 别留假旋钮: " + staleExemptions);
    }

    /** 断言 3：Qdrant 集合名的单一事实来源没有被绕过。 */
    @Test
    void qdrantCollectionNameIsSourcedFromAppRagOnly() {
        // 占位符在 Environment 里已被解析, 这里能验的是"两处取值一致";
        // "写成引用而非第二处字面量"由 KbOnlyDefaultBindingTest 那类原文级守卫负责(application.yaml 注释亦声明)
        assertEquals(appProperties.getRag().getCollectionName(),
                environment.getProperty("spring.ai.vectorstore.qdrant.collection-name"),
                "Qdrant 写集合与 app.rag.collection-name 必须是同一个值, 否则会出现'删不掉自己写的向量'");
    }

    /** 断言 4：安全基线最终生效值。 */
    @Test
    void securityBaselineDefaultsHold() {
        assertTrue(appProperties.getChat().isKbOnly(),
                "kb-only 必须默认开启: 关掉等于允许模型编造公司内部信息");
        assertTrue(!appProperties.getAuth().isDevUserHeaderEnabled(),
                "X-User-Id 模拟登录绝不允许在仓库配置里打开(身份伪造)");
        assertTrue(!appProperties.getDemo().isSeedEnabled(),
                "播种默认必须关闭: 仓库自带可用口令就是泄漏面");
        assertTrue(appProperties.getDemo().getAdminPassword().isEmpty(),
                "播种口令不得有默认值");
        assertTrue(appProperties.getAuth().getJwtSecret().isEmpty(),
                "JWT 密钥不得进仓库(留空时非生产生成一次性随机密钥, 生产拒绝启动)");
        // 黑名单放行是刻意的可用性取舍, 但它必须是"看得见"的决定而不是无人认领的默认值
        assertTrue(appProperties.getAuth().isBlacklistFailOpen(),
                "blacklist-fail-open=true 是开发期可用性取舍(prod 由 SecurityConfigValidator 强制 false);"
                        + " 改成 false 前先确认那段校验仍在");
    }

    /**
     * 该键是否真的落到了 bean 的某个字段上——沿 getter 逐级下钻, 任一级取不到即为孤儿键。
     *
     * <p>不能"造一个新 AppProperties 只绑这一个键"来判定: 实测那样恒为真(类型转换会把任意字符串喂给
     * 任何字段, 未绑定异常又只在顶层绑定收尾时抛)。
     */
    private boolean boundToField(String canonicalKey) {
        Object current = appProperties;
        String[] segments = canonicalKey.split("[.]");
        for (int i = 1; i < segments.length; i++) {
            // 列表键在 Environment 里带下标(app.cors.allowed-origins[0]), 字段名要先把下标摘掉
            String segment = segments[i].replaceAll("\\[\\d+]", "");
            try {
                current = read(current, segment);
            } catch (ReflectiveOperationException e) {
                return false;
            }
            if (current == null) {
                return false;
            }
        }
        // 走到最后一级说明整条路径都有对应字段
        return true;
    }

    /** boolean 字段是 {@code isXxx()}, 其余是 {@code getXxx()}; 两种命名各试一次。 */
    private static Object read(Object host, String kebab) throws ReflectiveOperationException {
        String suffix = capitalize(kebab);
        try {
            return host.getClass().getMethod("get" + suffix).invoke(host);
        } catch (NoSuchMethodException e) {
            return host.getClass().getMethod("is" + suffix).invoke(host);
        }
    }

    private static String capitalize(String kebab) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }
}
