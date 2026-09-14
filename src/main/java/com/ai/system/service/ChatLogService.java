package com.ai.system.service;

import com.ai.common.PageResult;
import com.ai.system.dto.ChatLogVO;
import com.ai.system.entity.ChatLog;
import com.ai.session.entity.ChatSession;
import com.ai.system.mapper.ChatLogMapper;
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
 * 对话日志业务(需求 6.1)：问答留痕写入与分页查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLogService {

    private final ChatLogMapper chatLogMapper;

    /**
     * 记录一次问答。
     *
     * @param session        会话
     * @param userMessage    用户提问
     * @param assistantReply AI 回答
     * @param sourcesJson    来源 JSON
     * @param toolCallsJson  工具调用 JSON(可为 null)
     * @param modelName      模型名
     * @param durationMs     耗时 ms
     */
    @Transactional
    public void record(ChatSession session, String userMessage, String assistantReply,
            String sourcesJson, String toolCallsJson, String modelName, Integer durationMs) {
        ChatLog logRow = new ChatLog();
        logRow.setSessionId(session.getSessionId());
        logRow.setUserId(session.getUserId());
        logRow.setUserMessage(userMessage);
        logRow.setAssistantReply(assistantReply);
        logRow.setSources(sourcesJson);
        logRow.setToolCalls(toolCallsJson);
        logRow.setModelName(modelName);
        logRow.setDurationMs(durationMs);
        chatLogMapper.insert(logRow);
    }

    /**
     * 对话日志分页查询。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param userId    用户过滤(可选)
     * @param startDate 创建时间起(可选)
     * @param endDate   创建时间止(可选)
     * @return 对话日志分页 VO
     */
    public PageResult<ChatLogVO> list(int pageNum, int pageSize, String sessionId,
            Long userId, LocalDate startDate, LocalDate endDate) {
        Page<ChatLog> mpPage = new Page<>(pageNum, pageSize);
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<ChatLog> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(sessionId), ChatLog::getSessionId, sessionId)
                .eq(userId != null, ChatLog::getUserId, userId)
                .ge(startDate != null, ChatLog::getCreatedAt, start)
                .lt(endDate != null, ChatLog::getCreatedAt, end)
                .orderByDesc(ChatLog::getId);
        chatLogMapper.selectPage(mpPage, qw);
        List<ChatLogVO> vos = mpPage.getRecords().stream().map(l -> new ChatLogVO(
                l.getId(), l.getSessionId(), l.getUserId(), l.getUserMessage(),
                l.getAssistantReply(), l.getSources(), l.getToolCalls(),
                l.getModelName(), l.getTotalTokens(), l.getDurationMs(),
                l.getCreatedAt())).toList();
        return PageResult.of(vos, mpPage.getTotal(), (int) mpPage.getCurrent(),
                (int) mpPage.getSize());
    }
}
