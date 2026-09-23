package com.ai.session.service;

import com.ai.common.Strings;
import com.ai.common.Timeouts;
import com.ai.config.AppProperties;
import com.ai.config.props.SessionTitleProps;
import com.ai.config.ChatClientProvider;
import com.ai.session.entity.ChatSession;
import com.ai.session.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 会话自动标题：新会话的第一轮提问结束时，"未命名会话"变成一个有意义的短标题。
 *
 * <p>两段式，各自解决一个明确问题：
 * <ol>
 *   <li><b>兜底</b>——{@link #claimFallback} 在回答开始前<b>同步</b>把问题截断存入标题列。
 *       零等待、零外部依赖，保证即使模型完全不可用也一定有标题；</li>
 *   <li><b>精修</b>——{@link #refineAsync} 把问题交给模型概括，<b>异步</b>覆盖兜底标题。
 *       它只需要问题、不需要答案，因此与本轮模型回答<b>并行</b>：回答 streamed 完的几秒里
 *       精修早已落库，前端在本轮结束后刷新一次即可拿到精修版。</li>
 * </ol>
 *
 * <p>并发与覆盖安全（三条规则缺一不可）：
 * <ul>
 *   <li>抢首轮用<b>条件更新</b>（{@code WHERE title IS NULL OR title=''}）而非"先读再写"，
 *       靠影响行数判定归属——用户快速连发两轮时只有一个请求能拿到精修权；</li>
 *   <li>精修回写同样带上"标题仍等于我写过的那个兜底值"的条件，用户在期间手动改名则精修作废，
 *       <b>自动标题永不覆盖人工标题</b>；</li>
 *   <li>两次写库都必须 {@link SessionCacheService#evict} ——{@code requireActive} 读的是 Redis 里的
 *       会话实体副本，不失效会长时间显示旧标题。</li>
 * </ul>
 *
 * <p>降级：模型未配置/超时/失败/输出清洗后为空，一律保留兜底标题并 WARN，绝不影响对话本身。
 * 与 {@code QueryRewriter}/{@code ConversationSummarizer} 同属"后台机械性压缩任务"，
 * 故同样以 {@code enable_thinking=false} 关闭思维链以压低延迟。
 */
@Slf4j
@Service
public class SessionTitleService {

    /** 兜底标题超长时的省略号 */
    private static final String ELLIPSIS = "…";
    /** 连续空白(含换行/制表)折为一个空格的正则 */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    /** 模型常见的"标题："前缀 */
    private static final Pattern TITLE_PREFIX = Pattern.compile("^(标题|题目)\\s*[：:]\\s*");
    /** 模型输出首尾该剥掉的标点(不含成对包裹符, 那些由 {@link #WRAPPING_PAIRS} 处理) */
    private static final String EDGE_PUNCTUATION = "。，、！？：；,.!…~～";
    /** 成对包裹符：仅当首尾正好配成一对时才剥掉 */
    private static final String[][] WRAPPING_PAIRS = {
            {"“", "”"}, {"‘", "’"}, {"\"", "\""}, {"「", "」"}, {"『", "』"},
            {"《", "》"}, {"〈", "〉"}, {"【", "】"}, {"(", ")"},
    };
    /**
     * 精修提示词。刻意内联而非放 {@code prompts/*.st}：模板目录属 prompt 模块的资产,
     * 让 session 模块为此依赖 {@code com.ai.prompt.PromptService} 会引入一条无谓的模块边。
     */
    private static final String REFINE_PROMPT = """
            为下面这句用户的第一句话拟一个简短标题, 用于在会话列表中辨识。
            要求: 不超过 12 个字; 用名词短语概括主题; 不要加引号或书名号或句末标点;\
            不要解释、不要回答这个问题、不要输出多余行。
            用户的话: \
            """;
    /** 送入模型的提问长度上限(防超长粘贴撑爆提示词) */
    private static final int PROMPT_QUESTION_MAX_CHARS = 500;

    private final ChatSessionMapper sessionMapper;
    private final SessionCacheService sessionCache;
    private final ChatClientProvider chatClientProvider;
    private final AppProperties appProperties;
    /** 标题精修专用线程池(队列满即丢弃, 绝不在请求线程上执行模型调用) */
    private final Executor sessionTitleExecutor;

    /**
     * 构造自动标题服务。
     *
     * @param sessionMapper       会话 Mapper(条件更新标题列)
     * @param sessionCache        会话 Redis 缓存(写库后必须失效)
     * @param chatClientProvider  模型客户端提供者(未配置时返回 null, 本类据此降级)
     * @param appProperties       配置(app.session-title.*)
     * @param sessionTitleExecutor 精修任务线程池
     */
    public SessionTitleService(ChatSessionMapper sessionMapper, SessionCacheService sessionCache,
            ChatClientProvider chatClientProvider, AppProperties appProperties,
            @Qualifier("sessionTitleExecutor") Executor sessionTitleExecutor) {
        this.sessionMapper = sessionMapper;
        this.sessionCache = sessionCache;
        this.chatClientProvider = chatClientProvider;
        this.appProperties = appProperties;
        this.sessionTitleExecutor = sessionTitleExecutor;
    }

    /* ==================== 对外两步入口 ==================== */

    /**
     * 首轮抢占式写入兜底标题(同步, 一条 SQL)。
     *
     * @param session   本轮会话实体(可能来自缓存, 其 title 仅作快速跳过依据)
     * @param question  本轮用户消息
     * @return 本次实际写入的兜底标题; 返回非 null 表示"本轮抢到了首轮", 调用方据此触发精修。
     *         标题已存在 / 问题为空白 / 并发下别人先写了 / 开关关闭 —— 一律返回 null
     */
    public String claimFallback(ChatSession session, String question) {
        // 实体多来自 Redis 缓存, title 非空即可零成本跳过绝大多数请求;
        // 万一缓存里是旧的空标题, 下面的条件更新也会挡住
        if (!config().isEnabled() || session == null
                || StringUtils.hasText(session.getTitle())) {
            return null;
        }
        String fallback = fallbackTitleOf(question);
        if (fallback == null) {
            return null;
        }
        if (claimByUpdate(session.getSessionId(), fallback) == 0) {
            return null; // 并发下别的请求已写入
        }
        sessionCache.evict(session.getSessionId());
        log.info("会话兜底标题已写入: session={}, title={}", session.getSessionId(), fallback);
        return fallback;
    }

    /**
     * 提交模型精修任务(立即返回, 不占用请求线程)。
     *
     * <p>必须由 {@link #claimFallback} 返回非 null 的调用方触发: 二者共用"抢到首轮"这一判据,
     * 保证一个会话只精修一次。
     *
     * @param sessionId     会话 ID
     * @param question      本轮用户消息
     * @param fallbackTitle 上一步写入的兜底标题(回写时的比对基准, 用于不覆盖人工改名)
     */
    public void refineAsync(String sessionId, String question, String fallbackTitle) {
        if (!config().isEnabled() || !config().isModelEnabled()) {
            return;
        }
        try {
            sessionTitleExecutor.execute(() -> refine(sessionId, question, fallbackTitle));
        } catch (RejectedExecutionException e) {
            // 队列满: 丢弃即可, 兜底标题已经落库, 不该为"标题更好看"拖慢对话
            log.warn("会话标题精修任务被拒(已降级: 保留截断标题): session={}, 原因 {}",
                    sessionId, e.getMessage());
        }
    }

    /* ==================== 兜底标题 ==================== */

    /**
     * 由提问生成兜底标题：折叠空白 → 截断到 {@code fallbackChars} → 超长补省略号。
     *
     * @param question 用户消息(可空)
     * @return 兜底标题; 提问为空白时返回 null(没有可展示的线索)
     */
    String fallbackTitleOf(String question) {
        if (question == null) {
            return null;
        }
        String collapsed = WHITESPACE_RUN.matcher(question).replaceAll(" ").trim();
        if (collapsed.isEmpty()) {
            return null;
        }
        int max = Math.max(1, config().getFallbackChars());
        return collapsed.length() <= max ? collapsed : Strings.truncate(collapsed, max) + ELLIPSIS;
    }

    /** 条件更新: 仅当标题仍为空时写入, 返回影响行数(1=抢到首轮) */
    private int claimByUpdate(String sessionId, String fallback) {
        ChatSession patch = new ChatSession();
        patch.setTitle(fallback);
        return sessionMapper.update(patch, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getSessionId, sessionId)
                .and(w -> w.isNull(ChatSession::getTitle).or().eq(ChatSession::getTitle, "")));
    }

    /* ==================== 模型精修 ==================== */

    /** 真正的精修执行体(在线程池线程上跑): 调模型 → 清洗 → 条件回写 */
    private void refine(String sessionId, String question, String fallbackTitle) {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            return; // 模型未配置: 兜底标题就是最终结果
        }
        long start = System.currentTimeMillis();
        try {
            var spec = client.prompt()
                    .system("你是会话标题生成器，只输出标题。")
                    .user(REFINE_PROMPT + Strings.truncate(question, PROMPT_QUESTION_MAX_CHARS));
            spec.options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .extraBody(Map.of("enable_thinking", false)));
            String raw = Timeouts.call(() -> spec.call().content(), config().getTimeoutMs());
            persistRefined(sessionId, raw, fallbackTitle);
            log.info("会话标题精修完成: session={}, 耗时 {}ms", sessionId,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("会话标题精修失败(已降级: 保留截断标题): session={}, 耗时 {}ms, 原因 {}",
                    sessionId, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    /**
     * 回写精修结果：只覆盖"我写过的那个兜底值"，其余情况(用户已改名/标题已被别的流程改动)放弃回写。
     *
     * @param sessionId     会话 ID
     * @param rawTitle      模型原始输出(可空)
     * @param fallbackTitle 本次的兜底标题, 作为 WHERE 比对基准
     */
    void persistRefined(String sessionId, String rawTitle, String fallbackTitle) {
        String title = cleanTitle(rawTitle);
        if (title == null || title.equals(fallbackTitle)) {
            return; // 清洗后为空或等同兜底: 没有信息增量, 不必写库
        }
        ChatSession patch = new ChatSession();
        patch.setTitle(title); // 只 SET 标题; sessionId 属于 WHERE 条件, 放进实体会被当成待更新列
        int rows = sessionMapper.update(patch, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getTitle, fallbackTitle));
        if (rows > 0) {
            sessionCache.evict(sessionId);
            log.info("会话标题已精修: session={}, {} => {}", sessionId, fallbackTitle, title);
        }
    }

    /**
     * 清洗模型输出：取首行 → 去"标题："前缀 → 反复剥首尾标点与成对包裹符 → 限长。
     *
     * <p>每轮循环都严格变短, 故必然收敛; 不追求穷举格式, 目标是"列表里不出现引号和句号"。
     *
     * @param raw 模型输出(可空)
     * @return 干净标题; 清洗后为空则返回 null
     */
    String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int newLine = s.indexOf('\n');
        if (newLine >= 0) {
            s = s.substring(0, newLine).trim(); // 模型多写了几行时只取第一行
        }
        s = TITLE_PREFIX.matcher(s).replaceFirst("");
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            String trimmed = trimEdgePunctuation(s);
            if (trimmed.length() != s.length()) {
                s = trimmed;
                changed = true;
            }
            String unwrapped = unwrapQuotes(s);
            if (unwrapped != null) {
                s = unwrapped.trim();
                changed = true;
            }
        }
        if (s.isEmpty()) {
            return null;
        }
        int max = Math.max(1, config().getMaxChars());
        return s.length() <= max ? s : Strings.truncate(s, max);
    }

    /** 剥掉首尾的裸标点(成对包裹符不在此列) */
    private static String trimEdgePunctuation(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && EDGE_PUNCTUATION.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && EDGE_PUNCTUATION.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }

    /** 首尾正好配成一对包裹符时剥掉; 不配对则原样返回 */
    private static String unwrapQuotes(String s) {
        if (s.length() < 2) {
            return null;
        }
        for (String[] pair : WRAPPING_PAIRS) {
            if (s.startsWith(pair[0]) && s.endsWith(pair[1])) {
                return s.substring(1, s.length() - pair[1].length());
            }
        }
        return null;
    }

    private SessionTitleProps config() {
        return appProperties.getSessionTitle();
    }
}
