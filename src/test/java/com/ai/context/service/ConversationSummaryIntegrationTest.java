package com.ai.context.service;

import com.ai.context.ConversationMemory;
import com.ai.memory.ChatMemoryArchive;
import com.ai.memory.ChatMemoryArchive.ArchivedEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 滚动摘要集成测试(真 MySQL + <b>下调触发阈值</b>): trigger-messages 20→2、keep-recent-messages 8→1,
 * 经 @SpringBootTest 测试属性覆盖——主配置零改动, 测试结束即"回调"。
 *
 * <p>验证"append 双写留档 → 触发摘要 → 裁剪工作表 → 留档 SUMMARY 事件"全链路的账目一致性:
 * seq 连续无撞键、batch 递增、工作表收敛到 keep。mock 级测试验证不了 seq 算术——
 * 生产曾在此链路报 {@code Duplicate entry … uk_conv_seq}(appendSummary 未 +1), 本测试钉死。
 *
 * <p>Qdrant 自动装配被排除(本测试不涉检索); LLM 摘要器以 mock 替身提供合格的结构化四节输出。
 * 依赖本地 MySQL(表由启动期 spring.sql.init 幂等创建)。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration",
        "app.context.summary.trigger-messages=2",
        "app.context.summary.keep-recent-messages=1"})
class ConversationSummaryIntegrationTest {

    /** 合格的结构化四节摘要(mock 摘要器的返回, 须通过 normalizeSections 校验) */
    private static final String STRUCTURED = """
            【会话意图】了解年假与调休制度
            【已确认事实】年假按入职年限计算; 调休 1:1
            【未决事项】加班费口径待确认
            【口径约束】用中文简洁回答
            """;

    @Autowired
    private ConversationMemory memory;
    @Autowired
    private ChatMemoryArchive archive;
    @MockitoBean
    private ConversationSummarizer summarizer;

    @Test
    void loweredThresholdTriggersSummaryAndLedgerStaysConsistent() throws Exception {
        // 纯 UUID(36 字符)对齐 conversation_id 列宽; 前缀会超长(VARCHAR(36))
        String sessionId = UUID.randomUUID().toString();
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new ConversationSummarizer.SummaryResult(STRUCTURED, true));

        // 两轮问答(4 条消息)即越过 trigger-messages=2
        memory.append(sessionId, "第一问", "第一答");
        memory.append(sessionId, "第二问", "第二答");
        memory.summarizeIfNeededAsync(sessionId);

        // @Async(auditExecutor): 轮询等待摘要落库(≤10s)
        for (int i = 0; i < 100 && memory.summaryOf(sessionId) == null; i++) {
            Thread.sleep(100);
        }
        assertTrue(memory.summaryOf(sessionId) != null, "10s 内摘要未落库(异步链路异常)");

        // 留档账目: 4 条消息原文 + 1 条 SUMMARY 压缩事件, seq 连续, batch=1
        List<ArchivedEntry> raw = archive.findByConversationId(sessionId, 100);
        assertEquals(5, raw.size(), "留档应含 4 条消息原文 + 1 条 SUMMARY 压缩事件");
        assertEquals("SUMMARY", raw.get(4).role());
        assertEquals(5, raw.get(4).seq(),
                "SUMMARY 行 seq 必须是最大值+1(回归: 曾用 MAX 值与最后一行消息撞 uk_conv_seq)");
        assertEquals(1, raw.get(4).batch());
        assertTrue(raw.get(0).content().contains("第一问"), "消息原文必须完整入档");

        // 工作表收敛到 keep-recent-messages=1
        assertEquals(1, memory.messageCount(sessionId));

        // 清理测试数据(留档/摘要均随会话删除的口径, 测试里手工清)
        memory.clearSummary(sessionId);
        archive.deleteByConversationId(sessionId);
    }
}
