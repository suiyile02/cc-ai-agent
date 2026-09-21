package com.ai.context.service;

import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.prompt.PromptService;
import com.ai.session.SessionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 短查询扩展器：把"产品""报销"这类过短提问补全成一句可检索的完整问题，只作用于检索链路。
 *
 * <p>存在的理由：embedding 对裸词的余弦分显著偏低（实测"产品"0.410，同一分块用完整句问得 0.720），
 * 而出口判据要求向量路过阈值——短词于是被误判为"知识库无依据"。扩展把分数拉回正常量级，
 * 不改用户原话（对话与日志仍用原文）。
 *
 * <p>三条边界：① 只对<b>非 AGENT 会话</b>且<b>去空白后长度 &lt; minChars</b> 的提问触发，
 * 完整句子零等待；② 失败/超时/结果为空一律回退原问题（WARN 节流），绝不为扩展而阻断对话；
 * ③ 扩展成功的轮次 {@code expanded=true}，调用方据此把它当"已改写"排除出语义缓存
 * ——缓存条目跨用户共享，拿扩写词检索出的答案挂到原始短词键上会让后来者拿到跑题内容。
 */
@Slf4j
@Service
public class ShortQueryExpander {

    private final AppProperties appProperties;
    private final ChatClientProvider chatClientProvider;
    private final PromptService promptService;

    /** 降级告警节流(模型端点故障时 60 秒一条) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    /**
     * @param appProperties      应用配置(app.context.short-query.*)
     * @param chatClientProvider 对话模型提供者(未配置模型时扩展整体跳过)
     * @param promptService      提示词模板
     */
    public ShortQueryExpander(AppProperties appProperties, ChatClientProvider chatClientProvider,
            PromptService promptService) {
        this.appProperties = appProperties;
        this.chatClientProvider = chatClientProvider;
        this.promptService = promptService;
    }

    /**
     * 扩展结果。
     *
     * @param query   用于检索的问题(扩展后或原样)
     * @param expanded 是否实际扩展过
     */
    public record ExpandResult(String query, boolean expanded) {
    }

    /**
     * 按需扩展短查询。
     *
     * @param sessionType 会话类型(AGENT 会话跳过——它靠工具不靠知识库)
     * @param query       待检索的问题(可能已被多轮改写)
     * @return 扩展结果; 不满足触发条件或任何失败均返回原问题(expanded=false)
     */
    public ExpandResult expand(SessionType sessionType, String query) {
        AppProperties.Context.ShortQuery cfg = appProperties.getContext().getShortQuery();
        if (!cfg.isEnabled() || sessionType == SessionType.AGENT || blank(query)) {
            return new ExpandResult(query, false);
        }
        if (query.replaceAll("\\s", "").length() >= cfg.getMinChars()) {
            return new ExpandResult(query, false);
        }
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            return new ExpandResult(query, false);
        }
        long start = System.currentTimeMillis();
        try {
            String prompt = promptService.template("prompts/short-query-expand.st")
                    .replace("{{query}}", query.trim());
            String raw = com.ai.common.Timeouts.call(() -> complete(prompt), cfg.getTimeoutMs());
            long elapsed = System.currentTimeMillis() - start;
            String expanded = sanitize(raw, cfg.getMaxChars());
            if (expanded == null || expanded.equals(query.trim())) {
                log.info("短查询扩展无变化: {}ms, query={}", elapsed, query);
                return new ExpandResult(query, false);
            }
            log.info("短查询扩展成功: {}ms, {} => {}", elapsed, query, expanded);
            return new ExpandResult(expanded, true);
        } catch (Exception e) {
            degraded.warn("短查询扩展失败/超时({}ms), 回退原问题: {}",
                    System.currentTimeMillis() - start, e.getMessage());
            return new ExpandResult(query, false);
        }
    }

    /**
     * 一次模型调用（单独抽出作测试替换点）。
     *
     * @param prompt 模板渲染后的用户消息
     * @return 模型输出文本
     */
    String complete(String prompt) {
        var spec = chatClientProvider.getIfAvailable().prompt()
                .system("你是检索查询补全器，只输出补全后的那一句问题。")
                .user(prompt);
        // 补全是机械性任务, 注入 enable_thinking=false 关闭 qwen3 思维链(与改写/摘要同口径)
        if (appProperties.getContext().getShortQuery().isDisableThinking()) {
            spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false)));
        }
        return spec.call().content();
    }

    /** 清洗模型输出: 去引号/前缀/换行, 只取第一行并截断到配置上限; 空则返回 null */
    private static String sanitize(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String first = raw.trim().split("\\R", 2)[0].trim();
        first = first.replaceAll("^[\"'“”『]|(?:[\"'“”』])$", "").replaceFirst("^(补全后|输出|问题)[:：]", "").trim();
        if (first.isEmpty()) {
            return null;
        }
        return first.length() > maxChars ? first.substring(0, maxChars) : first;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
