package com.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ChatClient 惰性提供者。
 *
 * <p>不能用 {@code @ConditionalOnBean(ChatModel.class)} 在配置类上创建 ChatClient——
 * 用户配置类的处理先于自动配置, 此时 ChatModel 的 Bean 定义尚不存在, 条件永远不成立,
 * 导致 ChatClient 缺失、对话接口全部降级(实测踩坑)。因此改为运行时从
 * {@link ObjectProvider} 惰性获取 ChatModel 并缓存构建结果。
 */
@Component
@RequiredArgsConstructor
public class ChatClientProvider {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private volatile ChatClient cached;

    /**
     * 获取 ChatClient.Builder(供需要定制请求链的组件使用, 如 CompressionQueryTransformer)。
     *
     * @return Builder, 模型未配置时 null
     */
    public ChatClient.Builder getBuilderIfAvailable() {
        ChatClient client = getIfAvailable();
        return client == null ? null : client.mutate();
    }

    /**
     * 获取 ChatClient; 模型未配置(API Key 缺失/自动装配未启用)时返回 null, 由调用方降级。
     *
     * @return ChatClient 或 null
     */
    public ChatClient getIfAvailable() {
        ChatClient client = cached;
        if (client != null) {
            return client;
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return null;
        }
        synchronized (this) {
            if (cached == null) {
                cached = ChatClient.builder(model)
                        // 全链路(prompt/响应)调试日志, DEBUG 级别可见
                        .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                        .build();
            }
            return cached;
        }
    }
}
