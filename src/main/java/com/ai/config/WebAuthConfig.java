package com.ai.config;

import com.ai.user.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册登录鉴权拦截器(保护 /api/** 业务接口),
 * 并为异步请求(SSE 流式)指定应用任务执行器(虚拟线程), 避免落到默认 SimpleAsyncTaskExecutor。
 */
@Configuration
@RequiredArgsConstructor
public class WebAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final ObjectProvider<AsyncTaskExecutor> taskExecutorProvider;

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
     * 异步请求(SSE)使用 Boot 的 applicationTaskExecutor(虚拟线程)。
     *
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        AsyncTaskExecutor executor = taskExecutorProvider.getIfAvailable();
        if (executor != null) {
            configurer.setTaskExecutor(executor);
        }
    }
}
