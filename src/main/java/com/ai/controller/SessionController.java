package com.ai.controller;

import com.ai.common.PageResult;
import com.ai.common.Result;
import com.ai.dto.SessionCreateRequest;
import com.ai.dto.SessionVO;
import com.ai.service.ChatSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理(需求第 5 章)。MVP 阶段以 X-User-Id 请求头模拟登录用户。
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessionService;

    /** 5.1 创建会话：POST /api/sessions */
    @PostMapping
    public Result<SessionVO> create(@RequestBody @Valid SessionCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        return Result.ok(sessionService.create(request.title(), request.effectiveType(), userId));
    }

    /** 5.2 会话列表：GET /api/sessions */
    @GetMapping
    public Result<PageResult<SessionVO>> list(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        return Result.ok(sessionService.list(userId, pageNum, pageSize));
    }

    /** 5.3 归档会话：PUT /api/sessions/{id}/archive */
    @PutMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        sessionService.archive(id, userId);
        return Result.ok("会话已归档");
    }

    /** 5.4 删除会话(软删 + 清理记忆)：DELETE /api/sessions/{id} */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        sessionService.delete(id, userId);
        return Result.ok("会话已删除");
    }
}
