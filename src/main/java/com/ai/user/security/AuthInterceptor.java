package com.ai.user.security;
import com.ai.user.security.JwtTokenProvider;
import com.ai.user.security.UserContext;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;
import com.ai.common.Result;
import com.ai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 登录鉴权拦截器：保护 /api/** 下的业务接口。
 *
 * <p>校验顺序：
 * <ol>
 *   <li>优先解析 {@code Authorization: Bearer <token>} 并写入 {@link UserContext};</li>
 *   <li>开发模式(app.auth.dev-user-header-enabled=true)下允许用 X-User-Id 请求头模拟;</li>
 *   <li>均缺失/无效 → 返回统一失败响应(code=5002/6005)。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** 无需登录的公开接口 */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/register", "/api/auth/login");

    private final JwtTokenProvider tokenProvider;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * 请求前校验并装载当前用户。
     *
     * @param request  请求
     * @param response 响应(用于直接写出 401 JSON)
     * @param handler  处理器
     * @return true=放行; false=已写出拒绝响应
     * @throws Exception 处理异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (!(handler instanceof HandlerMethod) || PUBLIC_PATHS.contains(uri)
                || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1) Bearer Token 优先
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                JwtTokenProvider.TokenPayload payload =
                        tokenProvider.parse(authorization.substring(7).trim());
                UserContext.set(new UserContext.CurrentUser(payload.userId(),
                        payload.username(), payload.role()));
                return true;
            } catch (BusinessException e) {
                writeUnauthorized(response);
                return false;
            }
        }

        // 2) 开发模式: X-User-Id 模拟登录
        String devUserId = request.getHeader("X-User-Id");
        if (appProperties.getAuth().isDevUserHeaderEnabled()
                && devUserId != null && !devUserId.isBlank()) {
            try {
                Long userId = Long.valueOf(devUserId.trim());
                // 开发模拟一律按普通用户; 管理员验证请走真实登录
                UserContext.set(new UserContext.CurrentUser(userId, null, UserContext.ROLE_USER));
                return true;
            } catch (NumberFormatException ignored) {
                // 非法值按未登录处理
            }
        }

        writeUnauthorized(response);
        return false;
    }

    /**
     * 请求结束清理 ThreadLocal, 防止线程复用导致用户串号。
     *
     * @param request  请求
     * @param response 响应
     * @param handler  处理器
     * @param ex       异常(可空)
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 写出统一格式的未授权响应。
     *
     * @param response 响应
     * @throws Exception 写出失败
     */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.TOKEN_INVALID, "未登录或凭证已失效，请先 /api/auth/login")));
    }
}
