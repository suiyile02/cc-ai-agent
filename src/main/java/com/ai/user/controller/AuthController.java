package com.ai.user.controller;
import com.ai.user.security.UserContext;
import com.ai.user.service.AuthService;

import com.ai.common.Result;
import com.ai.user.dto.AuthVO;
import com.ai.user.dto.LoginRequest;
import com.ai.user.dto.RegisterRequest;
import com.ai.user.dto.UserVO;
import com.ai.user.security.JwtTokenProvider.TokenPayload;
import com.ai.user.security.LoginContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户鉴权接口：注册 / 登录 / 当前用户信息。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册(注册成功即返回登录态)。
     *
     * @param request 注册请求(用户名/密码/昵称)
     * @return 统一响应, data 为 AuthVO(token + user)
     */
    @PostMapping("/register")
    public Result<AuthVO> register(@RequestBody @Valid RegisterRequest request) {
        return Result.ok("注册成功", authService.register(request));
    }

    /**
     * 用户登录。
     *
     * @param request 登录请求(用户名/密码)
     * @return 统一响应, data 为 AuthVO(token + user)
     */
    @PostMapping("/login")
    public Result<AuthVO> login(@RequestBody @Valid LoginRequest request,
            HttpServletRequest httpRequest) {
        return Result.ok("登录成功", authService.login(
                new LoginContext(request, clientIp(httpRequest))));
    }

    /**
     * 提取客户端真实 IP(优先反代头, 无则取远端地址)。
     *
     * @param request HTTP 请求
     * @return 客户端 IP
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 退出登录：拉黑当前令牌(jti 写入 Redis 黑名单, TTL=令牌剩余有效期)。
     *
     * @param request HTTP 请求(拦截器已解析并挂载 tokenPayload)
     * @return 统一响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        if (request.getAttribute("tokenPayload")
                instanceof TokenPayload payload) {
            long remaining = payload.expiresAt() - System.currentTimeMillis();
            authService.logout(payload, remaining);
        }
        return Result.ok("已退出登录");
    }

    /**
     * 当前用户信息(需携带 Authorization: Bearer token)。
     *
     * @return 统一响应, data 为当前用户
     */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me(UserContext.requireUserId()));
    }
}
