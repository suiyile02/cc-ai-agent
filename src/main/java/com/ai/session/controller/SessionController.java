package com.ai.session.controller;
import com.ai.session.service.ChatSessionService;

import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.session.dto.SessionCreateRequest;
import com.ai.session.dto.SessionMessagesVO;
import com.ai.session.dto.SessionRenameRequest;
import com.ai.session.dto.SessionVO;
import com.ai.user.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口(需求第 5 章)。当前用户取自登录态(AuthInterceptor → {@link UserContext})。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;

    /**
     * 创建会话(5.1)。
     *
     * @param request 请求体(title + sessionType)
     * @return 统一响应, data 为会话 VO(含 sessionId)
     */
    @PostMapping
    public Result<SessionVO> create(@RequestBody @Valid SessionCreateRequest request) {
        Long userId = UserContext.requireUserId();
        return Result.ok(sessionService.create(request.title(), request.effectiveType(), userId));
    }

    /**
     * 会话列表(5.2, 仅当前用户)。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 统一响应, data 为会话分页
     */
    @GetMapping
    public Result<PageResult<SessionVO>> list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = UserContext.requireUserId();
        return Result.ok(sessionService.list(userId, pageNum, pageSize));
    }

    /**
     * 单个会话详情: 前端在首轮对话结束后刷新自动生成的标题用(比拉整页列表更轻)。
     *
     * @param id 会话主键
     * @return 统一响应, data 为会话 VO
     */
    @GetMapping("/{id}")
    public Result<SessionVO> detail(@PathVariable Long id) {
        return Result.ok(sessionService.detail(id, UserContext.requireUserId()));
    }

    /**
     * 会话历史消息(会话回显): 记忆中的 USER/ASSISTANT 消息 + 滚动摘要。
     *
     * @param id 会话主键
     * @return 统一响应, data 为 {messages, summary}
     */
    @GetMapping("/{id}/messages")
    public Result<SessionMessagesVO> messages(@PathVariable Long id) {
        Long userId = UserContext.requireUserId();
        return Result.ok(sessionService.listMessages(id, userId));
    }

    /**
     * 归档会话(5.3)。
     *
     * @param id 会话主键
     * @return 统一响应(无 data)
     */
    @PutMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        sessionService.archive(id, UserContext.requireUserId());
        return Result.ok("会话已归档");
    }

    /**
     * 会话改名(人工标题优先, 之后不会被自动标题覆盖)。
     *
     * @param id      会话主键
     * @param request 请求体(title 必填 ≤200)
     * @return 统一响应, data 为改名后的会话 VO
     */
    @PutMapping("/{id}/title")
    public Result<SessionVO> rename(@PathVariable Long id,
            @RequestBody @Valid SessionRenameRequest request) {
        return Result.ok(sessionService.rename(id, request.title(), UserContext.requireUserId()));
    }

    /**
     * 删除会话(5.4)：软删 + 清理记忆。
     *
     * @param id 会话主键
     * @return 统一响应(无 data)
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sessionService.delete(id, UserContext.requireUserId());
        return Result.ok("会话已删除");
    }
}
