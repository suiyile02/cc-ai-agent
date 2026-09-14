package com.ai.context.service;

import com.ai.common.MessageTextRenderer;
import com.ai.common.Timeouts;
import com.ai.common.TokenCounter;
import com.ai.prompt.PromptService;
import com.ai.config.ChatClientProvider;
import com.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 历史滚动摘要器：把较早的多轮对话压缩为要点式摘要, 供后续对话作为 SYSTEM 上下文注入。
 *
 * <p>模型不可用/生成失败时返回 {@code updated=false}, 由调用方保留原始历史(不裁剪), 保证不阻断主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummarizer {

    private final ChatClientProvider chatClientProvider;
    private final PromptService promptService;
    private final TokenCounter tokenCounter;
    private final AppProperties appProperties;

    /**
     * 摘要结果。
     *
     * @param summary 摘要文本(updated=false 时为传入的原摘要)
     * @param updated 是否成功生成了新摘要(false 表示降级/失败, 调用方不应裁剪历史)
     */
    public record SummaryResult(String summary, boolean updated) {

        /** 未更新(保留原摘要) */
        public static SummaryResult unchanged(String summary) {
            return new SummaryResult(summary, false);
        }
    }

    /**
     * 合并「已有摘要 + 新增老对话」生成新的滚动摘要。
     *
     * @param existingSummary 已有摘要(可为 null/空)
     * @param oldMessages     需要并入摘要的老消息(按时间升序)
     * @return 摘要结果; 模型不可用或失败时 updated=false
     */
    public SummaryResult summarize(String existingSummary, List<Message> oldMessages) {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null || oldMessages == null || oldMessages.isEmpty()) {
            return SummaryResult.unchanged(existingSummary);
        }
        int maxTokens = appProperties.getContext().getSummary().getMaxTokens();
        // 使用promptService获取模板，并替换模板中的占位符
        String prompt = promptService.template("prompts/history-summary.st")
                .replace("{{existingSummary}}",                                    // 替换现有摘要占位符
                        existingSummary == null || existingSummary.isBlank() ? "(无)" : existingSummary)    // 如果摘要为空或空白字符串，则替换为"(无)"
                .replace("{{conversation}}", MessageTextRenderer.render(oldMessages))                   // 替换对话内容占位符，使用render方法处理旧消息
                .replace("{{maxTokens}}", String.valueOf(maxTokens));               // 替换最大令牌数占位符，将maxTokens转换为字符串
        try {
            long timeoutMs = appProperties.getContext().getSummary().getTimeoutMs();
            var spec = client.prompt()
                    .system("你是精确、克制的对话摘要器。")
                    .user(prompt);
            // 摘要是机械性压缩任务, 关闭 qwen3 思维链加快后台就绪
            if (appProperties.getContext().getSummary().isDisableThinking()) {
                spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .extraBody(Map.of("enable_thinking", false)));
            }
            String answer = Timeouts.call(() -> spec.call().content(), timeoutMs);
            if (answer == null || answer.isBlank()) {
                return SummaryResult.unchanged(existingSummary);
            }
            String trimmed = tokenCounter.truncateToTokens(answer.trim(), maxTokens);
            return new SummaryResult(trimmed, true);
        } catch (Exception e) {
            log.warn("历史摘要生成失败, 保留原摘要: {}", e.getMessage());
            return SummaryResult.unchanged(existingSummary);
        }
    }
}
