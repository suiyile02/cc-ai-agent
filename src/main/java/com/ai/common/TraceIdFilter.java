package com.ai.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 全链路追踪过滤器(可观测性一期)：为每个请求生成/透传 traceId 并写入 MDC,
 * 使本次请求在**任何线程**打出的日志都自动携带同一 traceId, 一次 grep 串联全链路。
 *
 * <p>规则：
 * <ul>
 *   <li>优先透传调用方带来的 {@code X-Request-Id}(网关/前端/压测工具可指定), 便于跨系统关联;</li>
 *   <li>缺失则生成 12 位短随机串(日志可读性与唯一性平衡);</li>
 *   <li>traceId 同时回写响应头 {@code X-Request-Id}——前端报障时带上它, 后端一条 grep 定位全部日志;</li>
 *   <li>异步段(审计落库/改写/检索/流式前置)的 MDC 传播由 {@code MdcTaskDecorator} 与
 *       {@code Timeouts}/{@code ChatService} 各自的捕获逻辑负责, 本过滤器只管入口与清理。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键名(logback pattern 以 %X{traceId} 引用) */
    public static final String TRACE_ID_KEY = "traceId";
    /** 请求/响应头名 */
    public static final String TRACE_HEADER = "X-Request-Id";

    /**
     * 解析或生成 traceId, 写入 MDC 并回写响应头, 请求结束后清理。
     *
     * @param request  请求
     * @param response 响应
     * @param chain    过滤链
     * @throws ServletException 过滤链异常(透传)
     * @throws IOException      IO 异常(透传)
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().substring(0, 12);
        }
        MDC.put(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear(); // 线程复用防串号(与 UserContext 清理同理)
        }
    }
}
