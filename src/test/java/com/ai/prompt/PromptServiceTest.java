package com.ai.prompt;

import com.ai.chat.service.ChatSourceDisplay;
import com.ai.config.AppProperties;
import com.ai.rag.ChatOutcome;
import com.ai.session.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptService} 提示词选择单元测试：覆盖「会话类型 × 出口 × 是否命中 × kb-only」矩阵。
 *
 * <p>P3-7 后入参从"意图路由预判(RagMode)"换成"检索事后算出的出口(ChatOutcome)"，
 * 因此本测试的每个用例都在断言**出口 → 模板**的对应关系，而不是词表 → 模板。
 * 纯字符串断言(读真实 classpath 模板)，不调模型。
 */
class PromptServiceTest {

    /** 严格模板的判定性句子 */
    private static final String STRICT_MARKER = "只能依据";
    /** 宽松自由问答模板的判定性句子 */
    private static final String OPEN_CHAT_MARKER = "常识、科普、闲聊";
    /** 宽松模式下资料块的补充规则 */
    private static final String LOOSE_EXTRA_RULE = "可适当补充常识性解释";
    /** 严格模式下资料块的补充规则 */
    private static final String STRICT_EXTRA_RULE = "禁止引入资料之外的知识";
    /** 部分命中必须先答可答部分(第 0 步实测该行为不稳定, 故显式钉进模板) */
    private static final String PARTIAL_RULE = "若资料只覆盖了问题的一部分";

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

    /* ---------------- AGENT 会话：出口不参与模板选择 ---------------- */

    @Test
    void agentSessionKeepsBasePromptRegardlessOfOutcome() {
        givenKbOnly(true);

        for (ChatOutcome outcome : ChatOutcome.values()) {
            String system = service.systemFor(SessionType.AGENT, outcome, false, null);
            assertFalse(system.contains(STRICT_MARKER),
                    "AGENT 会话靠工具作答, 不应被套上知识库严格模板: " + outcome);
            assertTrue(system.contains("企业内部"), "AGENT 应仍使用 base-system.st: " + outcome);
        }
    }

    /* ---------------- 有知识库依据 ---------------- */

    @Test
    void answeredFromKbInjectsContextWithLooseRule() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.RAG, ChatOutcome.ANSWERED_FROM_KB,
                true, "员工年假按入职年限计算。");

        assertTrue(system.contains(LOOSE_EXTRA_RULE), "宽松模式保留原有的「可补充常识」许可");
        assertFalse(system.contains(STRICT_EXTRA_RULE));
        assertTrue(system.contains("员工年假按入职年限计算"), "资料正文必须已注入");
    }

    @Test
    void answeredFromKbInjectsContextWithStrictRuleAndPartialHandling() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, ChatOutcome.ANSWERED_FROM_KB,
                true, "员工年假按入职年限计算。");

        assertTrue(system.contains(STRICT_EXTRA_RULE), "严格模式下资料块不得再邀请模型补充常识");
        assertTrue(system.contains(PARTIAL_RULE), "部分命中必须先答可答部分——实测该行为不稳定");
        assertFalse(system.contains(LOOSE_EXTRA_RULE), "两条规则互斥, 同时出现即自相矛盾");
    }

    @Test
    void cacheHitSharesTheKbTemplatePath() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, ChatOutcome.ANSWERED_FROM_CACHE,
                true, "资料");

        assertTrue(system.contains(STRICT_MARKER), "缓存命中的回答同样源自知识库, 模板口径应一致");
    }

    /* ---------------- 无据可依 ---------------- */

    @Test
    void refusedNoEvidenceAlwaysUsesStrictTemplate() {
        for (boolean kbOnly : new boolean[]{true, false}) {
            givenKbOnly(kbOnly);

            String system = service.systemFor(SessionType.HYBRID, ChatOutcome.REFUSED_NO_EVIDENCE,
                    false, null);

            assertTrue(system.contains(STRICT_MARKER), "拒答出口必须用严格模板(kbOnly=" + kbOnly + ")");
            assertFalse(system.contains(OPEN_CHAT_MARKER), "拒答轮次绝不能再授权自由作答");
        }
    }

    @Test
    void refusalSentenceMatchesNegativeCacheConstant() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.RAG, ChatOutcome.REFUSED_NO_EVIDENCE, false, null);

        // ChatSourceDisplay.NO_RESULT_ANSWER 是负缓存命中时用户看到的固定回答,
        // 严格模板要求模型复述同一句——两处措辞分叉, 用户会看到两种不同的"没找到"。
        // prompt 模块禁止依赖 chat 模块, 无法共享常量, 故用本测试钉住。
        assertTrue(system.contains(ChatSourceDisplay.NO_RESULT_ANSWER),
                "严格模板的拒答口径必须与 ChatSourceDisplay.NO_RESULT_ANSWER 完全一致");
    }

    /* ---------------- 自由作答轮 ---------------- */

    @Test
    void openAnswerUsesGeneralTemplateInBothModes() {
        // ANSWERED_OPEN 只在"允许自由作答"或"本轮不检索(AGENT 之外的非 RAG 会话)"时出现,
        // 两种情况都不该被套上"必须拒答"的严格模板
        for (boolean kbOnly : new boolean[]{true, false}) {
            givenKbOnly(kbOnly);

            String system = service.systemFor(SessionType.HYBRID, ChatOutcome.ANSWERED_OPEN,
                    false, null);

            assertTrue(system.contains(OPEN_CHAT_MARKER), "自由作答出口应使用 general-system(kbOnly=" + kbOnly + ")");
            assertFalse(system.contains(STRICT_MARKER));
        }
    }

    /* ---------------- 工具轮(回归防护: 不得被套成"可回答常识科普闲聊"后自由编造) ---------------- */

    @Test
    void toolTurnUnderKbOnlyUsesStrictTemplateButKeepsTools() {
        givenKbOnly(true);

        String system = service.systemFor(SessionType.HYBRID, ChatOutcome.TOOL_DATA, false, null);

        assertFalse(system.contains(OPEN_CHAT_MARKER),
                "严格模式下工具轮不得拿「常识科普闲聊」模板——否则开关形同虚设");
        assertTrue(system.contains("工具"), "严格模式必须仍允许调工具, 否则「查订单」会被误拒");
    }

    @Test
    void toolTurnWithoutKbOnlyKeepsGeneralTemplate() {
        givenKbOnly(false);

        String system = service.systemFor(SessionType.HYBRID, ChatOutcome.TOOL_DATA, false, null);

        assertTrue(system.contains(OPEN_CHAT_MARKER), "宽松模式下工具轮行为与本批改造前一致");
    }

    /* ---------------- 默认值与模板加载 ---------------- */

    @Test
    void strictModeIsTheProjectDefault() {
        assertTrue(new AppProperties().getChat().isKbOnly(),
                "P3-7 起严格知识库模式为默认; 若这里为 false 说明默认值被改动而本测试未同步");
    }

    @Test
    void everyBranchResolvesToNonEmptyTemplate() {
        for (boolean kbOnly : new boolean[]{true, false}) {
            givenKbOnly(kbOnly);
            for (ChatOutcome outcome : ChatOutcome.values()) {
                for (boolean hasHits : new boolean[]{true, false}) {
                    String system = service.systemFor(SessionType.HYBRID, outcome, hasHits, "资料");
                    assertTrue(system.length() > 80, "分支 " + outcome + "/hasHits=" + hasHits
                            + " 提示词过短, 疑似模板文件读取失败(load 失败返回空串)");
                }
            }
        }
    }
}
