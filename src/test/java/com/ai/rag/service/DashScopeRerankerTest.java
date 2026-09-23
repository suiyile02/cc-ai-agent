package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DashScopeReranker} 的解析与降级测试(不触网: 全部走"拿不到结果"的路径)。
 *
 * <p>刻意覆盖三条降级入口——密钥缺失、响应结构不符、HTTP 失败——因为它们共同保证一件事:
 * <b>重排环节永远不会让本轮失去上下文</b>。真实端点连通性由 docs/seed 下的脚本单独验(需要密钥)。
 */
class DashScopeRerankerTest {

    private final ScoreFusionReranker score = new ScoreFusionReranker();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 造一个语义路候选 */
    private static RetrievalCandidate candidate(String text, double semanticScore, int chunk) {
        Document doc = Document.builder().text(text)
                .metadata(Map.of("file_name", "a.md", "doc_id", "1", "chunk_index", chunk))
                .build();
        return new RetrievalCandidate(doc, semanticScore, null, chunk + 1, null);
    }

    private DashScopeReranker reranker(AppProperties props, String chatApiKey) {
        return new DashScopeReranker(props, score, mapper, chatApiKey);
    }

    @Test
    void missingApiKeyEverywhereFallsBackWithoutTouchingNetwork() {
        AppProperties props = new AppProperties();
        props.getRag().setRerankMode("api");
        List<RetrievalCandidate> candidates = List.of(candidate("年假 10 天", 0.6, 0));

        List<RetrievalCandidate> result = reranker(props, "").rerank("年假几天", candidates);

        assertSame(score.rerank("年假几天", candidates).get(0).doc(), result.get(0).doc(),
                "无密钥时必须回退分数融合而不是报错");
    }

    @Test
    void blankDedicatedKeyReusesChatApiKeyButStillCannotReachEndpoint() {
        AppProperties props = new AppProperties();
        // 专属密钥留空 → 复用对话侧密钥; 这里给个不可达地址, 验证的是"带着密钥也会安全回退"
        props.getRag().setRerankBaseUrl("http://127.0.0.1:1");
        props.getRag().setRerankTimeoutMs(500);
        List<RetrievalCandidate> candidates = List.of(candidate("年假 10 天", 0.6, 0));

        List<RetrievalCandidate> result = reranker(props, "sk-not-used").rerank("年假几天", candidates);

        assertEquals(candidates.size(), result.size(), "连不上时结果条数必须不变(整批回退)");
    }

    @Test
    void endpointUrlIsBuiltFromHostPlusFixedPath() {
        AppProperties props = new AppProperties();
        props.getRag().setRerankBaseUrl("https://example.test/compatible-mode/v1/");

        // base-url 允许带任意前缀(个人 MaaS 实例的 host 结构与公共端点不同), 代码只在
        // "没写过排序路径"时补齐固定路径, 且尾斜杠不得产生双斜杠
        var endpoint = new DashScopeReranker(props, score, mapper, "k").endpointForTest();
        assertEquals("https://example.test/compatible-mode/v1"
                        + com.ai.config.props.RagProps.RERANK_PATH,
                endpoint.url(), "尾斜杠要收掉、路径补齐且不重复");
    }

    @Test
    void fullUrlInBaseUrlIsNotDoubleAppended() {
        AppProperties props = new AppProperties();
        props.getRag().setRerankBaseUrl("https://maas.test/api/v1/services/rerank/text-rerank/text-rerank");

        var endpoint = new DashScopeReranker(props, score, mapper, "k").endpointForTest();

        assertEquals("https://maas.test/api/v1/services/rerank/text-rerank/text-rerank",
                endpoint.url(), "有人把整条 URL 填进 base-url 时不该再拼一次路径");
    }

    @Test
    void parsesResultsByIndexAndSortsByRelevance() throws Exception {
        AppProperties props = new AppProperties();
        List<RetrievalCandidate> sent = List.of(
                candidate("第一条", 0.6, 0), candidate("第二条", 0.55, 1), candidate("第三条", 0.5, 2));
        // 接口按相关度返回, 顺序被打乱: index=2 最高分
        String json = """
                {"output":{"results":[
                  {"index":2,"relevance_score":0.91},
                  {"index":0,"relevance_score":0.44},
                  {"index":1,"relevance_score":0.12}]}}""";

        List<RetrievalCandidate> ordered = new DashScopeReranker(props, score, mapper, "k")
                .parseResults(json, sent);

        assertEquals(List.of("第三条", "第一条", "第二条"),
                ordered.stream().map(c -> c.doc().getText()).toList(),
                "必须按 index 找回原候选并按 relevance_score 降序");
        assertEquals(0.91, ordered.get(0).fusedScore(), 1e-9);
    }

    @Test
    void malformedOrOutOfRangeResultsYieldEmptySoCallerFallsBack() throws Exception {
        List<RetrievalCandidate> sent = List.of(candidate("第一条", 0.6, 0));
        var reranker = new DashScopeReranker(new AppProperties(), score, mapper, "k");

        for (String json : List.of(
                "{}",
                "{\"output\":{}}",
                "{\"output\":{\"results\":[]}}",
                "{\"output\":{\"results\":[{\"index\":9,\"relevance_score\":0.9}]}}",
                "{\"output\":{\"results\":[{\"index\":0}]}}")) {
            assertTrue(reranker.parseResults(json, sent).isEmpty(),
                    "结构不符/越界/缺分的响应要判为\"没有结果\"从而回退: " + json);
        }
    }

    @Test
    void candidatesBeyondTheConfiguredLimitKeepTheirRrfOrderAtTheTail() throws Exception {
        AppProperties props = new AppProperties();
        props.getRag().setRerankCandidates(2);
        props.getRag().setRerankBaseUrl("http://127.0.0.1:1");
        List<RetrievalCandidate> many = List.of(
                candidate("a", 0.6, 0), candidate("b", 0.58, 1),
                candidate("c", 0.57, 2), candidate("d", 0.56, 3));

        var result = new DashScopeReranker(props, score, mapper, "sk-x").rerank("q", many);

        assertEquals(many.size(), result.size(), "未送排序的候选不能被丢弃, 只能接在后面");
    }
}
