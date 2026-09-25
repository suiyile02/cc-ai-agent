package com.ai.config;

import com.ai.user.security.AuthInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录鉴权拦截器(保护 /api/** 业务接口),
 * 并为异步请求(SSE 流式)指定 Boot 的 taskScheduler(虚拟线程),
 * 避免落到默认 SimpleAsyncTaskExecutor 或与业务线程池(ingestionExecutor)产生装配歧义。
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AsyncTaskExecutor asyncTaskExecutor;

    public WebAuthConfig(AuthInterceptor authInterceptor,
            @Qualifier("taskScheduler") @Nullable AsyncTaskExecutor asyncTaskExecutor) {
        this.authInterceptor = authInterceptor;
        this.asyncTaskExecutor = asyncTaskExecutor;
    }

    /**
     * 注册拦截器：拦截全部 /api/** 接口；注册/登录接口在拦截器内部放行。
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * 注册全链路追踪过滤器(最高优先级)：为每个请求写入 traceId 到 MDC 并回写响应头,
     * 使本次请求所有日志(含异步段)可串联。Servlet Filter 天然先于 AuthInterceptor 执行。
     *
     * @param traceIdFilter 追踪过滤器(TraceIdFilter, common 包)
     * @return 追踪过滤器注册
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.ai.common.TraceIdFilter> traceIdFilterRegistration(
            com.ai.common.TraceIdFilter traceIdFilter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<com.ai.common.TraceIdFilter> reg =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(traceIdFilter);
        reg.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        return reg;
    }

    /**
     * 异步请求(SSE)使用 Boot 的 applicationTaskExecutor(虚拟线程)。
     *
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        if (asyncTaskExecutor != null) {
            configurer.setTaskExecutor(asyncTaskExecutor);
        }
    }
}
