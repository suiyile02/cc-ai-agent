package com.ai.system.controller;
import com.ai.system.service.ChatLogService;
import com.ai.system.service.ContextLogService;
import com.ai.system.service.RagDecisionLogService;
import com.ai.system.service.ToolCallLogService;

import com.ai.common.DateParamUtils;
import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.rag.IntentCacheAdmin;
import com.ai.rag.SemanticCacheAdmin;
import com.ai.system.dto.ChatLogVO;
import com.ai.system.dto.ContextLogVO;
import com.ai.system.dto.RagDecisionLogVO;
import com.ai.system.dto.ToolCallLogVO;
import com.ai.user.security.RequireAdmin;
import com.ai.user.security.RequireSelfOrAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理接口(需求第 6 章)：对话日志 / 工具调用日志 / RAG 决策日志查询,
 * 以及语义缓存、意图路由缓存的运维清空(仅供管理员)。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final ChatLogService chatLogService;
    private final ToolCallLogService toolCallLogService;
    private final RagDecisionLogService ragDecisionLogService;
    private final ContextLogService contextLogService;
    private final SemanticCacheAdmin semanticCacheAdmin;
    private final IntentCacheAdmin intentCacheAdmin;

    /**
     * 对话日志分页查询(6.1)。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param userId    用户过滤(可选)
     * @param startTime 创建时间起(yyyy-MM-dd, 可选)
     * @param endTime   创建时间止(yyyy-MM-dd, 可选)
     * @return 统一响应, data 为对话日志分页
     */
    @GetMapping("/chat-logs")
    @RequireSelfOrAdmin(userIdParam = "userId")
    public Result<PageResult<ChatLogVO>> listChatLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<ChatLogVO> page = chatLogService.list(pageNum, pageSize, sessionId,
                userId, DateParamUtils.parseDate(startTime), DateParamUtils.parseDate(endTime));
        return Result.ok(page);
    }

    /**
     * 工具调用日志分页查询(6.2)。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param toolName  工具名过滤(可选)
     * @param status    状态过滤(可选)
     * @param startTime 创建时间起(可选)
     * @param endTime   创建时间止(可选)
     * @return 统一响应, data 为工具日志分页
     */
    @GetMapping("/tool-call-logs")
    @RequireSelfOrAdmin(userIdParam = "userId")
    public Result<PageResult<ToolCallLogVO>> listToolCallLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<ToolCallLogVO> page = toolCallLogService.list(pageNum, pageSize, sessionId,
                userId, toolName, status, DateParamUtils.parseDate(startTime),
                DateParamUtils.parseDate(endTime));
        return Result.ok(page);
    }

    /**
     * RAG 意图路由决策日志分页查询(6.3, 增强)。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param ragMode   意图模式 KB/GENERAL 过滤(可选)
     * @param startTime 创建时间起(可选)
     * @param endTime   创建时间止(可选)
     * @return 统一响应, data 为决策日志分页
     */
    @GetMapping("/rag-decisions")
    @RequireSelfOrAdmin(userIdParam = "userId")
    public Result<PageResult<RagDecisionLogVO>> listRagDecisions(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String ragMode,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<RagDecisionLogVO> page = ragDecisionLogService.list(pageNum, pageSize,
                sessionId, userId, ragMode, DateParamUtils.parseDate(startTime),
                DateParamUtils.parseDate(endTime));
        return Result.ok(page);
    }

    /**
     * 上下文装配日志分页查询(6.4, 增强)：各段 Token 占用/截断/改写审计。
     *
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @param sessionId 会话过滤(可选)
     * @param startTime 创建时间起(可选)
     * @param endTime   创建时间止(可选)
     * @return 统一响应, data 为上下文装配日志分页
     */
    @GetMapping("/context-logs")
    @RequireSelfOrAdmin(userIdParam = "userId")
    public Result<PageResult<ContextLogVO>> listContextLogs(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        PageResult<ContextLogVO> page = contextLogService.list(pageNum, pageSize, sessionId,
                userId, DateParamUtils.parseDate(startTime), DateParamUtils.parseDate(endTime));
        return Result.ok(page);
    }

    /**
     * 清空语义缓存(管理员)。知识库文档变更、对话模型切换均已由缓存键自动隔离失效, 此接口用于
     * "回答质量异常"等需要人工强制失效的场景(键含知识库版本号, 自增后旧键即不可命中)。
     *
     * @return 统一响应, data 为失效后的知识库版本号(-1 表示缓存未启用或 Redis 不可用)
     */
    @DeleteMapping("/semantic-cache")
    @RequireAdmin
    public Result<Long> clearSemanticCache() {
        return Result.ok(semanticCacheAdmin.evictAll());
    }

    /**
     * 清空意图路由缓存(管理员)。路由结果默认 TTL 60 分钟自然过期; 修改路由词表
     * (关键字/同义词)后调用此接口, 按版本号立即失效, 无需等 TTL。
     *
     * @return 统一响应, data 为失效后的路由版本号(-1 表示 Redis 不可用)
     */
    @DeleteMapping("/intent-cache")
    @RequireAdmin
    public Result<Long> clearIntentCache() {
        return Result.ok(intentCacheAdmin.evictAll());
    }
}
