package com.ai.system.service;

import com.ai.common.PageResult;
import com.ai.system.dto.RagDecisionLogVO;
import com.ai.system.entity.RagDecisionLog;
import com.ai.system.mapper.RagDecisionLogMapper;
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
 * RAG 意图路由决策日志业务：每次对话的决策自动写入, 供系统管理审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDecisionLogService {

    private final RagDecisionLogMapper mapper;

    /**
     * 保存一条 RAG 决策日志(对话模块每次请求时调用)。
     *
     * @param entry 决策记录
     */
    @Transactional
    public void save(RagDecisionLog entry) {
        mapper.insert(entry);
        log.debug("RAG 决策落库: mode={}, executed={}, semantic={}, keyword={}, final={}, cost={}ms",
                entry.getRagMode(), entry.getRetrievalExecuted(), entry.getSemanticHits(),
                entry.getKeywordHits(), entry.getFinalHits(), entry.getDurationMs());
    }

    /**
     * RAG 决策日志分页查询。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param ragMode   意图模式过滤 KB/GENERAL(可选)
     * @param startDate 创建时间起(可选)
     * @param endDate   创建时间止(可选)
     * @return 决策日志分页 VO
     */
    public PageResult<RagDecisionLogVO> list(int pageNum, int pageSize, String sessionId,
            Long userId, String ragMode, LocalDate startDate, LocalDate endDate) {
        Page<RagDecisionLog> mpPage = new Page<>(pageNum, pageSize);
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<RagDecisionLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(sessionId), RagDecisionLog::getSessionId, sessionId)
                .eq(userId != null, RagDecisionLog::getUserId, userId)
                .eq(StringUtils.hasText(ragMode), RagDecisionLog::getRagMode, ragMode)
                .ge(startDate != null, RagDecisionLog::getCreatedAt, start)
                .lt(endDate != null, RagDecisionLog::getCreatedAt, end)
                .orderByDesc(RagDecisionLog::getId);
        mapper.selectPage(mpPage, qw);
        List<RagDecisionLogVO> vos = mpPage.getRecords().stream().map(l -> new RagDecisionLogVO(
                l.getId(), l.getSessionId(), l.getUserMessage(), l.getRagMode(),
                l.getSessionType(), l.getRetrievalExecuted(), l.getSemanticHits(),
                l.getKeywordHits(), l.getFinalHits(), l.getTopK(),
                l.getSimilarityThreshold(), l.getRerankMode(), l.getDurationMs(),
                l.getCreatedAt())).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }
}
