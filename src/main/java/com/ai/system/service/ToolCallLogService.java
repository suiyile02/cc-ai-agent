package com.ai.system.service;

import com.ai.common.PageResult;
import com.ai.system.dto.ToolCallLogVO;
import com.ai.system.entity.ToolCallLog;
import com.ai.system.mapper.ToolCallLogMapper;
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
 * 工具调用日志业务(需求 4.4 / 6.2)：AOP 切面写入, 系统管理分页查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallLogService {

    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 保存一条工具调用日志(由 ToolCallLogAspect 调用)。
     *
     * @param entry 待保存日志
     */
    @Transactional
    public void save(ToolCallLog entry) {
        toolCallLogMapper.insert(entry);
        log.debug("工具调用日志: {} 耗时 {}ms 状态 {}",
                entry.getToolName(), entry.getDurationMs(), entry.getStatus());
    }

    /**
     * 工具调用日志分页查询。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param toolName  工具名过滤(可选)
     * @param status    状态过滤(可选)
     * @param startDate 创建时间起(可选)
     * @param endDate   创建时间止(可选)
     * @return 工具日志分页 VO
     */
    public PageResult<ToolCallLogVO> list(int pageNum, int pageSize, String sessionId,
            Long userId, String toolName, String status, LocalDate startDate, LocalDate endDate) {
        Page<ToolCallLog> mpPage = new Page<>(pageNum, pageSize);
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<ToolCallLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(sessionId), ToolCallLog::getSessionId, sessionId)
                .eq(userId != null, ToolCallLog::getUserId, userId)
                .eq(StringUtils.hasText(toolName), ToolCallLog::getToolName, toolName)
                .eq(StringUtils.hasText(status), ToolCallLog::getStatus, status)
                .ge(startDate != null, ToolCallLog::getCreatedAt, start)
                .lt(endDate != null, ToolCallLog::getCreatedAt, end)
                .orderByDesc(ToolCallLog::getId);
        toolCallLogMapper.selectPage(mpPage, qw);
        List<ToolCallLogVO> vos = mpPage.getRecords().stream().map(l -> new ToolCallLogVO(
                l.getId(), l.getSessionId(), l.getUserId(), l.getToolName(), l.getInputParams(),
                l.getOutputResult(), l.getStatus(), l.getDurationMs(),
                l.getErrorMessage(), l.getCreatedAt())).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }
}
