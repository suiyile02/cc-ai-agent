package com.ai.rag.service;

import com.ai.rag.ChatOutcome;
import com.ai.rag.OutcomeResolver;
import com.ai.rag.RetrievalOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link OutcomeResolver} 出口判定单元测试。
 *
 * <p>这里是 P3-7 的判定核心：**出口由检索的事实事后算出，不再由关键词表事前猜测**。
 * 三条最容易写错、且都有真实事故背景的规则单独钉住：
 * ① 工具轮不得被判成拒答；② 未执行检索(AGENT/非 RAG 会话)不等于"知识库无答案"；
 * ③ 只有 BM25 命中而语义路未过阈值时不算"有据可依"。
 */
class OutcomeResolverTest {

    private static final double THRESHOLD = 0.45;

    /** 构造一次检索结果 */
    private static RetrievalOutcome retrieved(boolean executed, double maxScore, int hitCount) {
        List<Document> hits = hitCount <= 0 ? List.of()
                : java.util.stream.IntStream.range(0, hitCount)
                        .mapToObj(i -> Document.builder().text("chunk" + i).build()).toList();
        return new RetrievalOutcome(hits, executed, hitCount, 10, false, maxScore);
    }

    private static ChatOutcome resolve(RetrievalOutcome outcome, boolean toolTurn, boolean kbOnly) {
        return OutcomeResolver.resolve(outcome, toolTurn, kbOnly, THRESHOLD);
    }

    /* ---------------- 工具轮 ---------------- */

    @Test
    void toolTurnIsNeverRefused() {
        // 工具轮刻意不跑知识库检索(结果必为空), 若按"无命中即拒答"判定, 问订单就会被拒——
        // 这正是实施时最容易踩的坑, 故单独钉死
        assertEquals(ChatOutcome.TOOL_DATA,
                resolve(RetrievalOutcome.none(), true, true));
        assertEquals(ChatOutcome.TOOL_DATA,
                resolve(RetrievalOutcome.none(), true, false));
    }

    /* ---------------- 有证据可依 ---------------- */

    @Test
    void semanticHitAboveThresholdWithHitsIsAnsweredFromKb() {
        assertEquals(ChatOutcome.ANSWERED_FROM_KB,
                resolve(retrieved(true, 0.72, 5), false, true));
        assertEquals(ChatOutcome.ANSWERED_FROM_KB,
                resolve(retrieved(true, 0.72, 5), false, false));
    }

    @Test
    void scoreExactlyAtThresholdCountsAsEvidence() {
        assertEquals(ChatOutcome.ANSWERED_FROM_KB,
                resolve(retrieved(true, THRESHOLD, 1), false, true));
    }

    /* ---------------- 无证据 ---------------- */

    @Test
    void belowThresholdRefusesWhenKbOnly() {
        assertEquals(ChatOutcome.REFUSED_NO_EVIDENCE,
                resolve(retrieved(true, 0.30, 0), false, true));
    }

    @Test
    void belowThresholdFallsBackToOpenAnswerWhenNotKbOnly() {
        assertEquals(ChatOutcome.ANSWERED_OPEN,
                resolve(retrieved(true, 0.30, 0), false, false));
    }

    @Test
    void keywordOnlyHitsWithoutSemanticEvidenceAreNotTreatedAsGrounded() {
        // BM25 在小语料上对任何问题都能凑满命中, 故"有 hits"不等于"有据可依"
        RetrievalOutcome keywordOnly = new RetrievalOutcome(
                List.of(Document.builder().text("无关但词面命中的块").build()), true, 0, 10, false, 0.12);

        assertEquals(ChatOutcome.REFUSED_NO_EVIDENCE, resolve(keywordOnly, false, true));
    }

    @Test
    void semanticAboveThresholdButZeroFinalHitsIsNotEvidence() {
        // 过阈值但重排后一段都没留下(极端配置)时不得声称"依据知识库作答"
        assertEquals(ChatOutcome.REFUSED_NO_EVIDENCE,
                resolve(retrieved(true, 0.80, 0), false, true));
    }

    /* ---------------- 未执行检索 ---------------- */

    @Test
    void notExecutedIsOpenAnswerNotRefusal() {
        // AGENT/非 RAG 会话根本不检索。把"没查"记成"查了且没答案"会让审计撒谎
        assertEquals(ChatOutcome.ANSWERED_OPEN,
                resolve(RetrievalOutcome.none(), false, true));
        assertEquals(ChatOutcome.ANSWERED_OPEN,
                resolve(RetrievalOutcome.none(), false, false));
    }

    @Test
    void degradedTimeoutIsNotTreatedAsNoAnswer() {
        // 检索超时降级: 零命中是"没查成"而非"确实无答案", 不得据此拒答
        assertEquals(ChatOutcome.ANSWERED_OPEN,
                resolve(RetrievalOutcome.executedEmpty(), false, true));
    }
}
