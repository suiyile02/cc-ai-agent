package com.ai.chat;

import com.ai.config.AppProperties;
import com.ai.prompt.PromptService;
import com.ai.rag.ChatOutcome;
import com.ai.rag.OutcomeResolver;
import com.ai.rag.RetrievalOutcome;
import com.ai.session.SessionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 严格知识库模式**热切换**集成测试(真上下文, 排除 Qdrant 自动装配): 不重启、只翻转
 * {@code ChatProps.kbOnly}(管理员按钮所走的同一条内存写路径), 断言下游两个消费点
 * ——出口判定({@link OutcomeResolver})与提示词选择({@link PromptService})——立即跟随。
 *
 * <p>场景: "KB 零命中 + HYBRID 会话(有工具)"的同一世界, 宽松/严格两种模式下的完整行为差异。
 * 测试结束在 finally 中还原标志位(等同"回调交付")。
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration")
class KbOnlyHotSwitchIntegrationTest {

    /** 宽松自由作答模板的判定性句子 */
    private static final String OPEN_MARKER = "常识、科普、闲聊";
    /** 严格模板的判定性句子 */
    private static final String STRICT_MARKER = "只能依据";
    private static final double THRESHOLD = 0.45;

    @Autowired
    private AppProperties appProperties;
    @Autowired
    private PromptService promptService;

    @Test
    void hotSwitchFlipsOutcomeAndPromptWithoutRestart() {
        boolean original = appProperties.getChat().isKbOnly();
        try {
            // yaml 安全基线默认严格
            assertTrue(original, "仓库默认必须是严格模式(yaml kb-only: true)");

            // 同一世界: 检索已执行、语义路零命中、HYBRID 会话有工具
            RetrievalOutcome zeroHit = new RetrievalOutcome(
                    List.<Document>of(), true, 0, 10, false, 0.2);

            // 宽松: 自由作答 + general-system 模板
            appProperties.getChat().setKbOnly(false);
            ChatOutcome loose = OutcomeResolver.resolve(zeroHit, true, false, THRESHOLD);
            assertEquals(ChatOutcome.ANSWERED_OPEN, loose, "宽松模式零命中 → 自由作答");
            String loosePrompt = promptService.systemFor(SessionType.HYBRID, loose, false, null);
            assertTrue(loosePrompt.contains(OPEN_MARKER), "宽松模式应使用自由作答模板");

            // 热切回严格(不重启): 同一世界 → 模型裁决 + 严格收口模板
            appProperties.getChat().setKbOnly(true);
            ChatOutcome strict = OutcomeResolver.resolve(zeroHit, true, true, THRESHOLD);
            assertEquals(ChatOutcome.NO_EVIDENCE_WITH_TOOLS, strict, "严格模式零命中+有工具 → 模型裁决");
            String strictPrompt = promptService.systemFor(SessionType.HYBRID, strict, false, null);
            assertTrue(strictPrompt.contains(STRICT_MARKER), "切回严格 → 拒答/工具裁决模板");
            assertTrue(strictPrompt.contains("以工具描述为准"), "严格收口须携带工具裁决指令");
        } finally {
            // 还原标志位, 避免污染同 JVM 内其它测试
            appProperties.getChat().setKbOnly(original);
        }
    }
}
