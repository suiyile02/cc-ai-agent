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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 历史滚动摘要器：把较早的多轮对话压缩为结构化摘要(固定四节), 供后续对话作为 SYSTEM 上下文注入。
 *
 * <p>四节定式(会话意图/已确认事实/未决事项/口径约束)保证信息密度与跨版本可比性;
 * 截断按节进行(预算均分), 避免整段截断时尾部小节整体消失。
 * 模型不可用/生成失败/**格式不合格**(缺节头)时返回 {@code updated=false},
 * 由调用方保留原始历史(不裁剪), 保证不阻断主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummarizer {

    /** 结构化摘要的四节节头(顺序固定; 模型输出缺任一节即判格式不合格) */
    static final String[] SECTION_HEADERS = {"【会话意图】", "【已确认事实】", "【未决事项】", "【口径约束】"};

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
                spec.options(OpenAiChatOptions.builder()
                        .extraBody(Map.of("enable_thinking", false)));
            }
            String answer = Timeouts.call(() -> spec.call().content(), timeoutMs);
            if (answer == null || answer.isBlank()) {
                return SummaryResult.unchanged(existingSummary);
            }
            String normalized = normalizeSections(answer, maxTokens);
            if (normalized == null) {
                // 模型没按四节定式输出: 保留旧摘要, 下轮写回后再触发(格式失败必须可见)
                log.warn("结构化摘要格式不合格(缺少必要节头), 保留原摘要下轮重试");
                return SummaryResult.unchanged(existingSummary);
            }
            return new SummaryResult(normalized, true);
        } catch (Exception e) {
            log.warn("历史摘要生成失败, 保留原摘要: {}", e.getMessage());
            return SummaryResult.unchanged(existingSummary);
        }
    }

    /**
     * 校验并规范化摘要: 四节节头必须齐全(缺任一节返回 null), 按固定节序重组并逐节截断
     * (token 预算均分到四节), 防止靠前的节挤占预算导致尾部小节被整段截掉。
     *
     * @param raw       模型原始输出
     * @param maxTokens 摘要总 token 上限
     * @return 规范化后的四节摘要; 格式不合格返回 null
     */
    String normalizeSections(String raw, int maxTokens) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int[] pos = new int[SECTION_HEADERS.length];
        for (int i = 0; i < SECTION_HEADERS.length; i++) {
            pos[i] = raw.indexOf(SECTION_HEADERS[i]);
            if (pos[i] < 0) {
                return null;
            }
        }
        // 模型可能不按模板节序输出: 每节内容取"本节头到下一个节头", 再按固定节序重组
        int perSection = Math.max(1, maxTokens / SECTION_HEADERS.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SECTION_HEADERS.length; i++) {
            int next = raw.length();
            for (int j = 0; j < SECTION_HEADERS.length; j++) {
                if (j != i && pos[j] > pos[i]) {
                    next = Math.min(next, pos[j]);
                }
            }
            String section = tokenCounter.truncateToTokens(raw.substring(pos[i], next).trim(), perSection);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(section);
        }
        return sb.toString();
    }
}
