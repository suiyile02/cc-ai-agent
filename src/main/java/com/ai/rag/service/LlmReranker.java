package com.ai.rag.service;

import com.ai.config.ChatClientProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code llm} 模式：让模型对候选片段按相关度排序（最多 {@value #MAX_CANDIDATES} 条）。
 *
 * <p>成本与失败处理：每轮额外一次模型调用；模型不可用、输出无法解析或调用异常一律
 * 回退 {@link ScoreFusionReranker}，绝不因重排失败让本轮没有上下文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LlmReranker implements RerankStrategy {

    /** 送模型排序的候选上限（再多超出提示词价值且拖慢） */
    private static final int MAX_CANDIDATES = 10;
    /** 单条候选在提示词里的片段长度上限 */
    private static final int SNIPPET_CHARS = 120;

    private final ChatClientProvider chatClientProvider;
    private final ScoreFusionReranker fallback;

    /** 对应配置值 {@code rerank-mode=llm}。 */
    @Override
    public String mode() {
        return "llm";
    }

    /**
     * 请求模型给出序号顺序并据此重排。
     *
     * @param query      检索问题
     * @param candidates RRF 顺序候选
     * @return 模型顺序的候选；不可用时为分数融合结果
     */
    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null || candidates.isEmpty()) {
            return fallback.rerank(query, candidates);
        }
        try {
            String answer = client.prompt()
                    .system("你是文档检索重排器。")
                    .user(buildPrompt(query, candidates))
                    .call().content();
            List<RetrievalCandidate> ordered = parseOrder(answer, candidates);
            return ordered.isEmpty() ? fallback.rerank(query, candidates) : ordered;
        } catch (Exception e) {
            log.warn("LLM 重排失败, 回退分数融合: {}", e.getMessage());
            return fallback.rerank(query, candidates);
        }
    }

    /** 组装"编号 + 片段"清单（编号从 0 起，与解析口径一致） */
    private String buildPrompt(String query, List<RetrievalCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(candidates.size(), MAX_CANDIDATES);
        for (int i = 0; i < limit; i++) {
            String text = candidates.get(i).doc().getText();
            sb.append(i).append(". ")
                    .append(text == null ? "" : text.substring(0, Math.min(text.length(), SNIPPET_CHARS)))
                    .append('\n');
        }
        return "根据问题重排以下候选知识片段, 只输出按相关度从高到低的序号(空格分隔), 不要解释。\n问题: "
                + query + "\n候选:\n" + sb;
    }

    /**
     * 解析模型输出的序号串：非数字分隔、越界序号与重复项丢弃。
     *
     * @param answer     模型回答（可空）
     * @param candidates 原候选
     * @return 解析出的顺序（可能为空表示未采纳模型结果）
     */
    private List<RetrievalCandidate> parseOrder(String answer, List<RetrievalCandidate> candidates) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<RetrievalCandidate> ordered = new ArrayList<>();
        for (String token : answer.trim().split("[^0-9]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int index = Integer.parseInt(token);
            if (index >= 0 && index < candidates.size() && !ordered.contains(candidates.get(index))) {
                ordered.add(candidates.get(index));
            }
        }
        return ordered;
    }
}
