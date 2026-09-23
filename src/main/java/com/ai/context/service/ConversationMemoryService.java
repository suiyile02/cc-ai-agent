package com.ai.context.service;
import com.ai.context.ConversationMemory;
import com.ai.context.HistoryContext;

import com.ai.common.TokenCounter;
import com.ai.config.AppProperties;
import com.ai.config.props.ContextProps;
import com.ai.memory.ChatMemoryAppender;
import com.ai.memory.ChatMemoryCounter;
import com.ai.context.entity.ConversationSummary;
import com.ai.context.mapper.ConversationSummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆服务(上下文管线自管历史)：读取(Token 受限窗口) / 写回 / 异步滚动摘要 / 清理。
 *
 * <p>替代原 {@code MessageChatMemoryAdvisor} 的自动读写：本服务直接基于 {@link ChatMemoryRepository}
 * 读写 {@code SPRING_AI_CHAT_MEMORY}。滚动摘要在每轮对话写回后由
 * {@link #summarizeIfNeededAsync(String)} 异步触发——后台合并既有摘要并生成新摘要、
 * 裁剪原始记录、落库 {@code conversation_summary}，请求路径不执行任何 LLM 调用；
 * 摘要就绪前的装配走"窗口历史 + Token 预算"兜底，不丢上下文。
 *
 * <p>对外以 {@link ConversationMemory} 契约暴露, 供 chat/session 等模块依赖接口调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService implements ConversationMemory {

    /** 摘要作为 SYSTEM 消息注入的角色/前缀/分隔开销 */
    private static final int SUMMARY_MESSAGE_OVERHEAD = 12;

    /**
     * 每会话互斥锁(带淘汰, 防内存泄漏): 串行化同一会话的记忆写回与摘要裁剪。
     * 上限 10 万会话, 30 分钟不访问即淘汰(活跃会话持续访问不会淘汰)。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> sessionLocks =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterAccess(java.time.Duration.ofMinutes(30))
                    .build();

    /** 正在执行异步摘要的会话集合(防同会话重复触发) */
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();

    private final ChatMemoryRepository memoryRepository;
    private final ChatMemoryAppender memoryAppender;
    private final ChatMemoryCounter memoryCounter;
    private final ConversationSummaryMapper summaryMapper;
    private final ConversationSummarizer summarizer;
    private final TokenCounter tokenCounter;
    private final AppProperties appProperties;

    /**
     * 读取会话历史：仅做窗口与 Token 预算收敛(摘要由后台异步任务负责, 请求路径无 LLM 调用)。
     *
     * @param sessionId         会话 ID
     * @param historyTokenBudget 历史段可用 Token 预算(含摘要)
     * @return 历史上下文(摘要 + 最近消息 + Token 计量)
     */
    @Transactional(readOnly = true)
    @Override
    public HistoryContext loadHistory(String sessionId, int historyTokenBudget) {
        // 同一会话加锁: 避免与并发 append/后台裁剪交错造成消息丢失
        synchronized (lockFor(sessionId)) {
            // 从数据库读取所有历史消息
            List<Message> all = new ArrayList<>(memoryRepository.findByConversationId(sessionId));
            // 读取会话摘要(由后台异步任务维护; 未就绪时为空, 装配走窗口历史兜底)
            String summary = readSummary(sessionId);
            // 标记是否被截断
            boolean truncated = false;

            int maxMessages = appProperties.getContext().getHistory().getMaxMessages();
            if (all.size() > maxMessages) {
                all = new ArrayList<>(all.subList(all.size() - maxMessages, all.size()));
                truncated = true;
            }

            int summaryTokens = summaryTokens(summary);
            int budgetForMessages = Math.max(0, historyTokenBudget - summaryTokens);
            List<Message> kept = new ArrayList<>();
            int used = 0;
            for (int i = all.size() - 1; i >= 0; i--) {
                Message m = all.get(i);
                int cost = tokenCounter.count(List.of(m));
                if (!kept.isEmpty() && used + cost > budgetForMessages) {
                    truncated = true;
                    break;
                }
                kept.add(0, m); // 保持时间升序
                used += cost;
            }
            return new HistoryContext(summary, kept, summaryTokens, summaryTokens + used, truncated);
        }
    }

    /**
     * 异步滚动摘要：每轮对话写回记忆后调用。满足触发条件(历史条数超阈值, 或 Token 超阈值)时,
     * 在后台完成"快照老消息 → 合并既有摘要生成新摘要 → 写入摘要并裁剪原始历史"。
     * LLM 调用不持有会话锁, 超时/失败仅保留现状, 由下一轮写回后再次判定触发。
     *
     * @param sessionId 会话 ID
     */
    @Async("auditExecutor")
    @Override
    public void summarizeIfNeededAsync(String sessionId) {
        if (!summarizing.add(sessionId)) {
            return; // 同一会话已有摘要任务在执行
        }
        try {
            // 获取滚动摘要配置
            ContextProps.Summary cfg = appProperties.getContext().getSummary();
            List<Message> snapshot;
            String existing;
            synchronized (lockFor(sessionId)) {
                List<Message> all = new ArrayList<>(memoryRepository.findByConversationId(sessionId));
                boolean countTrigger = all.size() > cfg.getTriggerMessages();
                boolean tokenTrigger = cfg.getTriggerTokens() > 0
                        && tokenCounter.count(all) > cfg.getTriggerTokens();
                if (!cfg.isEnabled() || all.size() <= cfg.getKeepRecentMessages()
                        || (!countTrigger && !tokenTrigger)) {
                    return;
                }
                existing = readSummary(sessionId);
                int keep = Math.min(cfg.getKeepRecentMessages(), all.size());
                snapshot = new ArrayList<>(all.subList(0, all.size() - keep));
            }

            ConversationSummarizer.SummaryResult result = summarizer.summarize(existing, snapshot);
            if (!result.updated()) {
                return; // 摘要失败: 保留现状(含全量历史), 下轮写回后再次触发
            }

            synchronized (lockFor(sessionId)) {
                List<Message> all = memoryRepository.findByConversationId(sessionId);
                int keep = Math.min(cfg.getKeepRecentMessages(), all.size());
                writeSummary(sessionId, result.summary());
                memoryRepository.saveAll(sessionId,
                        new ArrayList<>(all.subList(all.size() - keep, all.size())));
                log.info("会话摘要已更新并裁剪历史: sessionId={}, 摘要 {} tok, 保留 {} 条消息",
                        sessionId, tokenCounter.count(result.summary()), keep);
            }
        } catch (Exception e) {
            log.warn("异步滚动摘要失败(保留现状, 下轮再触发): {}", e.getMessage());
        } finally {
            summarizing.remove(sessionId);
        }
    }

    /**
     * 写回本轮对话(用户提问 + 助手回答)到会话记忆。
     *
     * @param sessionId     会话 ID
     * @param userText      用户消息(空则跳过)
     * @param assistantText 助手回答(空则跳过)
     */
    @Transactional
    @Override
    public void append(String sessionId, String userText, String assistantText) {
        // append-only 写回: 只插入本轮新增 2 条(O(新增) 次 SQL), 不再"全删全插"重写历史;
        // 持锁与摘要裁剪的 saveAll 互斥, 防止裁剪误删刚插入的消息
        List<Message> added = new ArrayList<>(2);
        if (userText != null && !userText.isBlank()) {
            added.add(new UserMessage(userText));
        }
        if (assistantText != null && !assistantText.isBlank()) {
            added.add(new AssistantMessage(assistantText));
        }
        if (added.isEmpty()) {
            return;
        }
        synchronized (lockFor(sessionId)) {
            memoryAppender.append(sessionId, added);
        }
    }

    /**
     * 取会话级互斥锁对象(不存在则创建)。
     *
     * @param sessionId 会话 ID
     * @return 锁对象
     */
    private Object lockFor(String sessionId) {
        return sessionLocks.get(sessionId, k -> new Object());
    }

    /**
     * 统计历史消息条数(用于查询改写的触发判定)。
     *
     * <p>走数据库 COUNT(*)({@link ChatMemoryCounter}), 不再"取出全部消息反序列化后取 size"
     * ——该调用每轮对话都会执行一次, 旧实现开销随历史长度线性增长。
     *
     * @param sessionId 会话 ID
     * @return 历史消息条数
     */
    @Transactional(readOnly = true)
    @Override
    public int messageCount(String sessionId) {
        return (int) memoryCounter.countByConversationId(sessionId);
    }

    /**
     * 返回最近的若干条历史消息(升序), 供查询改写参考。
     *
     * @param sessionId 会话 ID
     * @param limit     最多返回条数
     * @return 最近消息(升序)
     */
    @Transactional(readOnly = true)
    @Override
    public List<Message> recentMessages(String sessionId, int limit) {
        List<Message> all = memoryRepository.findByConversationId(sessionId);
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /**
     * 清理会话摘要(会话删除时调用)。
     *
     * @param sessionId 会话 ID
     */
    @Transactional
    @Override
    public void clearSummary(String sessionId) {
        summaryMapper.deleteById(sessionId);
    }

    /**
     * 读取会话滚动摘要文本(历史回显用)。
     *
     * @param sessionId 会话 ID
     * @return 摘要文本, 无摘要返回 null
     */
    @Override
    public String summaryOf(String sessionId) {
        return readSummary(sessionId);
    }

    /**
     * 读取会话摘要文本。
     *
     * @param sessionId 会话 ID
     * @return 摘要文本, 无记录返回 null
     */
    private String readSummary(String sessionId) {
        ConversationSummary s = summaryMapper.selectById(sessionId);
        return s == null ? null : s.getSummaryText();
    }

    /**
     * 写入/更新会话摘要(不存在则插入)。
     *
     * @param sessionId 会话 ID
     * @param text      摘要文本
     */
    private void writeSummary(String sessionId, String text) {
        LocalDateTime now = LocalDateTime.now();
        ConversationSummary existing = summaryMapper.selectById(sessionId);
        if (existing == null) {
            ConversationSummary s = new ConversationSummary();
            s.setConversationId(sessionId);
            s.setSummaryText(text);
            s.setSummarizedUntil(now);
            summaryMapper.insert(s);
        } else {
            existing.setSummaryText(text);
            existing.setSummarizedUntil(now);
            summaryMapper.updateById(existing);
        }
    }

    /**
     * 估算摘要段 Token(含 SYSTEM 注入开销; 无摘要返回 0)。
     *
     * @param summary 摘要文本
     * @return Token 估算值
     */
    private int summaryTokens(String summary) {
        if (summary == null || summary.isBlank()) {
            return 0;
        }
        return tokenCounter.count(summary) + SUMMARY_MESSAGE_OVERHEAD;
    }
}
