package com.ai.system.service;

import com.ai.common.PageResult;
import com.ai.system.dto.ContextLogVO;
import com.ai.system.entity.ContextLog;
import com.ai.system.mapper.ContextLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 上下文装配可观测日志业务：每次问答的 Token 组成/截断/改写自动写入, 供系统管理审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextLogService {

    private final ContextLogMapper mapper;

    /**
     * 保存一条上下文装配日志(对话模块每次请求时调用)。
     *
     * @param entry 装配记录
     */
    @Transactional
    public void save(ContextLog entry) {
        mapper.insert(entry);
        log.debug("上下文装配落库: mode={}, total={}tok, history={}条, truncated={}, rewritten={}",
                entry.getIntentMode(), entry.getTotalTokens(), entry.getHistoryMessages(),
                entry.getTruncated(), entry.getRewrittenQuery() != null);
    }

    /**
     * 上下文装配日志分页查询。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param startDate 创建时间起(可选)
     * @param endDate   创建时间止(可选)
     * @return 装配日志分页 VO
     */
    public PageResult<ContextLogVO> list(int pageNum, int pageSize, String sessionId,
            Long userId, LocalDate startDate, LocalDate endDate) {
        Page<ContextLog> mpPage = new Page<>(pageNum, pageSize);
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<ContextLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(sessionId), ContextLog::getSessionId, sessionId)
                .eq(userId != null, ContextLog::getUserId, userId)
                .ge(startDate != null, ContextLog::getCreatedAt, start)
                .lt(endDate != null, ContextLog::getCreatedAt, end)
                .orderByDesc(ContextLog::getId);
        mapper.selectPage(mpPage, qw);
        List<ContextLogVO> vos = mpPage.getRecords().stream().map(l -> new ContextLogVO(
                l.getId(), l.getSessionId(), l.getUserMessage(), l.getRewrittenQuery(),
                l.getIntentMode(), l.getSystemTokens(), l.getHistoryTokens(),
                l.getSummaryTokens(), l.getRagTokens(), l.getUserTokens(),
                l.getTotalTokens(), l.getModelMaxTokens(), l.getHistoryMessages(),
                l.getTruncated(), l.getDurationMs(), l.getCreatedAt())).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }
}
