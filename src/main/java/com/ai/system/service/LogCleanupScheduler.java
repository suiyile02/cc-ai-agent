package com.ai.system.service;

import com.ai.config.AppProperties;
import com.ai.config.props.LogRetentionProps;
import com.ai.memory.entity.ChatMemoryRecord;
import com.ai.memory.mapper.ChatMemoryRecordMapper;
import com.ai.system.entity.ChatLog;
import com.ai.system.entity.ContextLog;
import com.ai.system.entity.RagDecisionLog;
import com.ai.system.entity.ToolCallLog;
import com.ai.system.mapper.ChatLogMapper;
import com.ai.system.mapper.ContextLogMapper;
import com.ai.system.mapper.RagDecisionLogMapper;
import com.ai.system.mapper.ToolCallLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日志保留期定时清理(P2-4)：按配置保留天数清理四类审计日志, 防止无限增长。
 *
 * <p>清理范围: chat_log / tool_call_log / rag_decision_log / context_log 中
 * created_at 早于 (now - retention-days) 的记录; 每天凌晨执行(可配 cron)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogCleanupScheduler {

    private final ChatLogMapper chatLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final RagDecisionLogMapper ragDecisionLogMapper;
    private final ContextLogMapper contextLogMapper;
    private final ChatMemoryRecordMapper chatMemoryRecordMapper;
    private final AppProperties appProperties;

    /**
     * 定时清理过期日志(可经 app.log-retention.enabled 关闭)。
     */
    @Scheduled(cron = "${app.log-retention.cron:0 0 3 * * ?}")
    public void cleanup() {
        LogRetentionProps cfg = appProperties.getLogRetention();
        if (!cfg.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(cfg.getRetentionDays());
        int chatLog = chatLogMapper.delete(new LambdaQueryWrapper<ChatLog>()
                .lt(ChatLog::getCreatedAt, cutoff));
        int toolLog = toolCallLogMapper.delete(new LambdaQueryWrapper<ToolCallLog>()
                .lt(ToolCallLog::getCreatedAt, cutoff));
        int ragLog = ragDecisionLogMapper.delete(new LambdaQueryWrapper<RagDecisionLog>()
                .lt(RagDecisionLog::getCreatedAt, cutoff));
        int ctxLog = contextLogMapper.delete(new LambdaQueryWrapper<ContextLog>()
                .lt(ContextLog::getCreatedAt, cutoff));
        int memory = chatMemoryRecordMapper.delete(
                new LambdaQueryWrapper<ChatMemoryRecord>()
                        .lt(ChatMemoryRecord::getTimestamp, cutoff));
        log.info("日志保留期清理完成: cutoff={}, chat={}, tool={}, rag={}, context={}, memory={}",
                cutoff, chatLog, toolLog, ragLog, ctxLog, memory);
    }
}
