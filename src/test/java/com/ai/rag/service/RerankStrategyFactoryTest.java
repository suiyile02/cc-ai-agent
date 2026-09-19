package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * {@link RerankStrategyFactory} 与 {@link LlmReranker} 回退行为测试：
 * 模式解析、未知值回退 score、模型不可用时不得让本轮失去上下文。
 */
class RerankStrategyFactoryTest {

    private final ScoreFusionReranker score = new ScoreFusionReranker();
    private final RrfOrderReranker rrfOrder = new RrfOrderReranker();

    private RerankStrategyFactory factory(String mode) {
        AppProperties props = new AppProperties();
        props.getRag().setRerankMode(mode);
        ChatClientProvider clientProvider = mock(ChatClientProvider.class);
        return new RerankStrategyFactory(
                List.of(score, rrfOrder, new LlmReranker(clientProvider, score)), props);
    }

    private RetrievalCandidate candidate(String id, Double sem, Double kw) {
        Document doc = Document.builder().id(id).text("t").build();
        return new RetrievalCandidate(doc, sem, kw, sem == null ? null : 2, kw == null ? null : 1);
    }

    @Test
    void resolvesConfiguredMode() {
        assertInstanceOf(LlmReranker.class, factory("llm").current());
        assertInstanceOf(RrfOrderReranker.class, factory("none").current());
        assertInstanceOf(ScoreFusionReranker.class, factory("score").current());
    }

    @Test
    void unknownOrBlankModeFallsBackToScore() {
        assertInstanceOf(ScoreFusionReranker.class, factory("gte-rerank").current());
        assertInstanceOf(ScoreFusionReranker.class, factory(null).current());
        assertInstanceOf(LlmReranker.class, factory("  LLM ").current(), "大小写与空白应被规范化");
    }

    @Test
    void missingScoreImplementationFailsFast() {
        AppProperties props = new AppProperties();
        props.getRag().setRerankMode("bogus");
        RerankStrategyFactory onlyRrf = new RerankStrategyFactory(List.of(rrfOrder), props);

        assertThrows(IllegalStateException.class, onlyRrf::current);
    }

    @Test
    void llmRerankerFallsBackWhenModelUnavailable() {
        List<RetrievalCandidate> candidates = List.of(
                candidate("a", 0.2, 9.0), candidate("b", 0.9, null));
        LlmReranker llm = new LlmReranker(mock(ChatClientProvider.class), score);

        List<RetrievalCandidate> ordered = llm.rerank("年假几天", candidates);

        assertEquals(2, ordered.size(), "回退路径必须保住全部候选");
        assertEquals("b", ordered.get(0).doc().getId(), "回退到 score 融合后按融合分降序");
    }

    @Test
    void rrfOrderModeKeepsSequenceAndScoresMonotonically() {
        List<RetrievalCandidate> candidates = List.of(
                candidate("a", 0.2, 9.0), candidate("b", 0.9, null));

        List<RetrievalCandidate> ordered = new RrfOrderReranker().rerank("q", candidates);

        assertEquals(List.of("a", "b"), ordered.stream().map(c -> c.doc().getId()).toList());
        assertEquals(1.0, ordered.get(0).fusedScore(), 1e-9, "none 模式给相对顺位分(首位置 1.0)");
        assertEquals(0.5, ordered.get(1).fusedScore(), 1e-9);
    }
}
