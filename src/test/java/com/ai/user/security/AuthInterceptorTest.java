package com.ai.user.security;

import com.ai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AuthInterceptor} 公开路径白名单测试。
 *
 * <p>回归背景(2026-10-13 线上事故): 新增 {@code POST /api/auth/demo-login} 时只写了控制器,
 * 忘记把它加进 {@code PUBLIC_PATHS}——登录页"演示账号"按钮的请求不带 token, 在到达控制器前
 * 被拦截器以 6005/401 拒绝, 表现为"演示登录 401"。本用例把"免密端点必须在白名单里"钉死。
 */
class AuthInterceptorTest {

    private AuthInterceptor interceptor;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(mock(JwtTokenProvider.class),
                mock(TokenBlacklistService.class), new AppProperties(), new ObjectMapper());
        response = mock(HttpServletResponse.class);
    }

    /** 构造指向 uri 的请求(无 Authorization 头——免密端点的真实形态) */
    private HttpServletRequest request(String uri) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("POST");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return request;
    }

    @Test
    void passwordlessEndpointsMustBeWhitelistedBeforeController() throws Exception {
        assertTrue(interceptor.preHandle(request("/api/auth/login"), response, mock(HandlerMethod.class)),
                "前置确认: login 一直在白名单");
        assertTrue(interceptor.preHandle(request("/api/auth/register"), response, mock(HandlerMethod.class)),
                "前置确认: register 一直在白名单");
        assertTrue(interceptor.preHandle(request("/api/auth/demo-login"), response, mock(HandlerMethod.class)),
                "免密端点必须放行——遗漏会在线上表现为'点演示账号返回 401'");
    }

    @Test
    void protectedPathWithoutTokenIsRejected() throws Exception {
        assertFalse(interceptor.preHandle(request("/api/knowledge/documents"), response,
                        mock(HandlerMethod.class)),
                "带 token 才能访问的业务路径不得因白名单测试而被放开");
    }
}
