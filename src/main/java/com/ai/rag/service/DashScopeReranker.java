package com.ai.rag.service;

import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.config.props.RagProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code api} 模式：调用 DashScope <b>文本排序</b>接口重排（{@code rerank-mode=api}）。
 *
 * <h2>为什么不是走 Spring AI</h2>
 * Spring AI 2.0.1 的 OpenAI 模块<b>没有</b> rerank 抽象(仓库里曾留过一行
 * {@code spring.ai.openai.rerank.model}, 静默无效, 已删)。DashScope 的排序接口也不是 OpenAI 兼容格式:
 * 它是原生 REST——请求体 {@code {model, input:{query, documents[]}, parameters:{top_n}}}、响应
 * {@code output.results[].{index, relevance_score}}。所以这里直接用 JDK HttpClient 打，不引额外依赖。
 *
 * <h2>失败一律回退，绝不让本轮没有上下文</h2>
 * 未配密钥/超时/非 2xx/响应结构不符/解析异常，全部回退 {@link ScoreFusionReranker} 并 WARN
 * (经 {@link WarnThrottle} 节流——端点持续故障时它每轮都会被触发)。理由与 LLM 重排一致:
 * 重排只影响"哪几段进 Top-K"，属于精度增益，不值得为它牺牲可用性。
 *
 * <h2>分数口径提醒</h2>
 * 本策略写入 {@code fusedScore} 的是模型给的 {@code relevance_score}(0~1 的相关度)，
 * 与 score 模式的融合分<b>不同源</b>；出口判据({@code OutcomeResolver})刻意只看语义路的余弦分，
 * 正是为了不被这种可替换的分数口径影响"有没有依据"的判定。
 *
 * <h2>实测过的端点行为(排查时先看这里)</h2>
 * 公共端点 {@code dashscope.aliyuncs.com} 下 {@code qwen3.7-text-rerank} 与 {@code gte-rerank-v2}
 * 均可用(同一 DashScope Key)；{@code gte-rerank}(v1) 返回 AccessDenied——别以为配置错了。
 * 另: 该接口会<b>间歇性</b>返回 {@code InvalidParameter: Required body invalid}——同一条请求体
 * 前一次失败、后两次成功。因此看到该报错先重试再怀疑代码; 而本类的降级路径(WARN + 回退 score)
 * 保证了即使端点持续抽风, 对话也只是失去重排增益而已。
 */
@Slf4j
@Component
class DashScopeReranker implements RerankStrategy {

    /** 单条文档送排序接口的字符上限(排序模型只看前若干字即可判断相关性, 全量送既贵又慢) */
    private static final int MAX_DOC_CHARS = 2000;

    /** base-url 里已经写了完整路径时的识别标记(避免重复拼接) */
    private static final String PATH_MARKER = "/services/rerank/";

    private final AppProperties appProperties;
    private final ScoreFusionReranker fallback;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** 对话侧密钥, 供专属密钥缺省时复用(同一 DashScope 账号通常同键) */
    private final String chatApiKey;
    private final WarnThrottle warnThrottle = WarnThrottle.of(log);

    DashScopeReranker(AppProperties appProperties, ScoreFusionReranker fallback,
                      ObjectMapper objectMapper,
                      @Value("${spring.ai.openai.api-key:}") String chatApiKey) {
        this.appProperties = appProperties;
        this.fallback = fallback;
        this.objectMapper = objectMapper;
        this.chatApiKey = chatApiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 对应配置值 {@code rerank-mode=api}。 */
    @Override
    public String mode() {
        return "api";
    }

    /**
     * 请求排序接口并按其返回顺序重排候选。
     *
     * @param query      检索问题
     * @param candidates RRF 顺序候选
     * @return 接口顺序的候选；任何不可用情形为分数融合结果
     */
    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Endpoint endpoint = resolveEndpoint();
        if (endpoint.apiKey().isBlank()) {
            warnThrottle.warn("重排接口未配置密钥(rerank-mode=api 但 app.rag.rerank-api-key 与 "
                    + "spring.ai.openai.api-key 均为空), 回退分数融合");
            return fallback.rerank(query, candidates);
        }
        int limit = Math.min(candidates.size(), Math.max(1, endpoint.candidates()));
        List<RetrievalCandidate> sent = candidates.subList(0, limit);
        try {
            List<RetrievalCandidate> ordered = parseResults(call(sent, query, limit, endpoint), sent);
            if (ordered.isEmpty()) {
                warnThrottle.warn("重排接口响应无可用结果, 回退分数融合");
                return fallback.rerank(query, candidates);
            }
            // 接口只回了前 limit 条(或更少), 未送出的候选保持原 RRF 顺序接在后面
            List<RetrievalCandidate> result = new ArrayList<>(ordered);
            result.addAll(candidates.subList(limit, candidates.size()));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            warnThrottle.warn("重排调用被中断, 回退分数融合");
            return fallback.rerank(query, candidates);
        } catch (UnavailableException e) {
            warnThrottle.warn(e.getMessage());
            return fallback.rerank(query, candidates);
        } catch (Exception e) {
            warnThrottle.warn("重排调用失败({}: {}) → 回退分数融合",
                    e.getClass().getSimpleName(), truncate(e.getMessage(), 160));
            return fallback.rerank(query, candidates);
        }
    }

    /** 发出请求并返回响应体；非 2xx 转为可读的降级原因。 */
    private String call(List<RetrievalCandidate> sent, String query, int limit, Endpoint endpoint)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .timeout(Duration.ofMillis(endpoint.timeoutMs()))
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(sent, query, limit, endpoint),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new UnavailableException("重排接口 HTTP " + response.statusCode() + ": "
                    + truncate(response.body(), 200) + " → 回退分数融合");
        }
        return response.body();
    }

    /** DashScope 文本排序请求体(top_n 取候选数: 只要顺序不要截断, 收敛到 Top-K 由编排层负责)。 */
    private String requestBody(List<RetrievalCandidate> sent, String query, int limit,
                               Endpoint endpoint) throws Exception {
        List<String> documents = new ArrayList<>(sent.size());
        for (RetrievalCandidate candidate : sent) {
            String text = candidate.doc().getText();
            documents.add(truncate(text == null ? "" : text, MAX_DOC_CHARS));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("top_n", limit);
        // return_documents=false 显式送出(而非省略): 公共端点实测该字段可带, 且明确不回传原文省带宽
        parameters.put("return_documents", false);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", endpoint.model());
        payload.put("input", input);
        payload.put("parameters", parameters);
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 解析 {@code output.results[]}：按 {@code index} 找回候选、以 {@code relevance_score} 作最终分。
     *
     * @return 相关度降序的候选；结构不符或字段缺失时为空列表(由调用方回退)
     */
    List<RetrievalCandidate> parseResults(String json, List<RetrievalCandidate> sent)
            throws Exception {
        JsonNode results = objectMapper.readTree(json).path("output").path("results");
        if (!results.isArray()) {
            return List.of();
        }
        List<RetrievalCandidate> ordered = new ArrayList<>();
        for (JsonNode node : results) {
            int index = node.path("index").asInt(-1);
            double score = node.path("relevance_score").asDouble(Double.NaN);
            if (index < 0 || index >= sent.size() || Double.isNaN(score)) {
                continue;
            }
            ordered.add(sent.get(index).withFused(score));
        }
        ordered.sort(Comparator.comparingDouble(RetrievalCandidate::fusedScore).reversed());
        return ordered;
    }

    /** 每次读配置快照: 运行期改 yaml/环境变量后无需重启即生效。 */
    Endpoint resolveEndpoint() {
        var rag = appProperties.getRag();
        String base = trimTrailingSlash(rag.getRerankBaseUrl());
        String url = base.contains(PATH_MARKER) ? base : base + RagProps.RERANK_PATH;
        return new Endpoint(url, rag.getRerankModel(), rag.getRerankTimeoutMs(),
                rag.getRerankCandidates(), firstNonBlank(rag.getRerankApiKey(), chatApiKey));
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return secondary == null ? "" : secondary;
    }

    private static String trimTrailingSlash(String value) {
        String s = value == null ? "" : value.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...";
    }

    /** 供同包单测直接检查 URL 拼装(不触网)。 */
    Endpoint endpointForTest() {
        return resolveEndpoint();
    }

    /** 供同包单测直接喂响应文本验证解析(不触网)。 */
    List<RetrievalCandidate> parseForTest(String json, List<RetrievalCandidate> sent) throws Exception {
        return parseResults(json, sent);
    }

    /** 一次调用所需的端点参数快照。 */
    record Endpoint(String url, String model, long timeoutMs, int candidates, String apiKey) {
    }

    /** 端点明确不可用(HTTP 非 2xx)时的可读降级原因。 */
    private static final class UnavailableException extends RuntimeException {
        private UnavailableException(String message) {
            super(message);
        }
    }
}
