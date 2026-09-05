package com.ai.controller;

import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.dto.ChatLogVO;
import com.ai.dto.ToolCallLogVO;
import com.ai.service.ChatLogService;
import com.ai.service.ToolCallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理(需求第 6 章)：对话日志 / 工具调用日志查询。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final ChatLogService chatLogService;
    private final ToolCallLogService toolCallLogService;

    /** 6.1 对话日志：GET /api/system/chat-logs */
    @GetMapping("/chat-logs")
    public Result<PageResult<ChatLogVO>> listChatLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<ChatLogVO> page = chatLogService.list(pageNum, pageSize, sessionId,
                userId, KnowledgeController.parseDate(startTime),
                KnowledgeController.parseDate(endTime));
        return Result.ok(page);
    }

    /** 6.2 工具调用日志：GET /api/system/tool-call-logs */
    @GetMapping("/tool-call-logs")
    public Result<PageResult<ToolCallLogVO>> listToolCallLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<ToolCallLogVO> page = toolCallLogService.list(pageNum, pageSize, sessionId,
                toolName, status, KnowledgeController.parseDate(startTime),
                KnowledgeController.parseDate(endTime));
        return Result.ok(page);
    }
}
