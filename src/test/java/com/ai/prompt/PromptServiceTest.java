package com.ai.prompt;

import com.ai.chat.service.ChatSourceDisplay;
import com.ai.config.AppProperties;
import com.ai.rag.RagMode;
import com.ai.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptService} 提示词选择单元测试：覆盖「会话类型 × 意图路由 × 是否有命中 × kb-only」
 * 的模板选择矩阵。重点是严格模式({@code app.chat.kb-only})下不再把 TOOL/GENERAL 授权给
 * "可以回答常识、科普、闲聊"的自由作答模板, 以及拒答口径与语义负缓存固定文案不漂移。
 *
 * <p>纯字符串断言(读真实 classpath 模板), 不调用模型。
 */
class PromptServiceTest {

    /** 严格模板的判定性句子(只出现在 kb-only 模板里) */
    private static final String STRICT_MARKER = "只能依据";
    /** 宽松 general 模板的判定性句子 */
    private static final String OPEN_CHAT_MARKER = "常识、科普、闲聊";
    /** 宽松模式下资料块给出的补充许可 */
    private static final String LOOSE_EXTRA_RULE = "可适当补充常识性解释";
    /** 严格模式下资料块的替代规则 */
    private static final String STRICT_EXTRA_RULE = "禁止引入资料之外的知识";

    private AppProperties appProperties;
    private PromptService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        service = new PromptService(appProperties);
    }

    private void givenKbOnly(boolean kbOnly) {
        appProperties.getChat().setKbOnly(kbOnly);
    }

    /* ---------------- AGENT 会话: 不受开关影响 ---------------- */

    @Test
    void agentSessionKeepsBasePromptEvenWhenKbOnlyEnabled() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.AGENT, RagMode.KB, false, null);

        assertFalse(system.contains(STRICT_MARKER), "AGENT 会话靠工具作答, 不应被套上知识库严格模板");
        assertTrue(system.contains("企业内部"), "AGENT 应仍使用 base-system.st");
    }

    /* ---------------- GENERAL 意图 ---------------- */

    @Test
    void generalIntentUsesOpenChatPromptWhenKbOnlyDisabled() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.HYBRID, RagMode.GENERAL, false, null);

        assertTrue(system.contains(OPEN_CHAT_MARKER), "默认(宽松)模式下闲聊类仍允许自由作答");
    }

    @Test
    void generalIntentUsesStrictPromptWhenKbOnlyEnabled() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.HYBRID, RagMode.GENERAL, false, null);

        assertTrue(system.contains(STRICT_MARKER), "严格模式下与库无关的问题不得自由作答");
        assertFalse(system.contains(OPEN_CHAT_MARKER), "严格模式不得再授权闲聊式作答");
    }

    /* ---------------- TOOL 意图(回归防护: 曾被一并授权自由作答) ---------------- */

    @Test
    void toolIntentIsNotGivenOpenChatLicenseUnderKbOnly() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.HYBRID, RagMode.TOOL, false, null);

        assertFalse(system.contains(OPEN_CHAT_MARKER),
                "工具类问题绝不能拿" + OPEN_CHAT_MARKER + "模板——等于把'保留内部工具'变成'工具+自由知识'");
        assertTrue(system.contains("工具"), "严格模式仍须允许调用业务工具查内部数据");
    }

    @Test
    void toolIntentUnchangedWhenKbOnlyDisabled() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.HYBRID, RagMode.TOOL, false, null);

        assertTrue(system.contains(OPEN_CHAT_MARKER), "开关关闭时行为必须与改动前完全一致");
    }

    /* ---------------- KB 意图 + 有命中: 资料块的补充规则随开关切换 ---------------- */

    @Test
    void kbWithHitsKeepsLooseExtraRuleWhenKbOnlyDisabled() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.RAG, RagMode.KB, true, "员工年假按入职年限计算。");

        assertTrue(system.contains(LOOSE_EXTRA_RULE), "宽松模式保留原有的'可补充常识'许可");
        assertFalse(system.contains(STRICT_EXTRA_RULE));
        assertTrue(system.contains("员工年假按入职年限计算"), "资料正文必须已注入");
    }

    @Test
    void kbWithHitsSwapsToStrictExtraRuleWhenKbOnlyEnabled() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, RagMode.KB, true, "员工年假按入职年限计算。");

        assertTrue(system.contains(STRICT_EXTRA_RULE), "严格模式下资料块不得再邀请模型补充常识");
        assertFalse(system.contains(LOOSE_EXTRA_RULE), "两条规则互斥, 同时出现即自相矛盾");
    }

    /* ---------------- KB 意图 + 零命中 ---------------- */

    @Test
    void kbWithoutHitsUsesBasePromptWhenKbOnlyDisabled() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.RAG, RagMode.KB, false, null);

        assertFalse(system.contains(STRICT_MARKER));
        assertTrue(system.contains("知识库中未找到相关信息"), "宽松模式仍要求如实说未找到");
    }

    @Test
    void kbWithoutHitsUsesStrictPromptWhenKbOnlyEnabled() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, RagMode.KB, false, null);

        assertTrue(system.contains(STRICT_MARKER));
    }

    /* ---------------- 拒答口径与负缓存固定文案同源(防漂移) ---------------- */

    @Test
    void strictRefusalSentenceMatchesNegativeCacheConstant() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, RagMode.KB, false, null);

        // ChatSourceDisplay.NO_RESULT_ANSWER 是"负缓存命中"时用户看到的固定回答,
        // 严格模板要求模型复述同一句话——两处措辞一旦分叉, 用户会看到两种不同的"没找到"。
        // 生产代码不能共享常量(prompt 模块禁止依赖 chat 模块), 故用本测试钉住。
        assertTrue(system.contains(ChatSourceDisplay.NO_RESULT_ANSWER),
                "严格模板的拒答口径必须与 ChatSourceDisplay.NO_RESULT_ANSWER 完全一致");
    }

    /* ---------------- 模板加载失败防护 ---------------- */

    @Test
    void everyBranchResolvesToNonEmptyTemplate() {
        givenKbOnly(true);

        for (RagMode mode : RagMode.values()) {
            for (boolean hasHits : new boolean[]{true, false}) {
                String system = service.systemFor(SessionType.HYBRID, mode, hasHits, "资料");
                assertTrue(system.length() > 80,
                        "分支 " + mode + "/hasHits=" + hasHits + " 提示词过短, 疑似模板文件读取失败(load 失败返回空串)");
            }
        }
    }
}
