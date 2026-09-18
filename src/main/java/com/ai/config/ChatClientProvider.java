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
 *
 * <p>本类同时是"当前实际生效模型名"的单一事实来源({@link #modelLabel()}),
 * 供对话日志与语义缓存键命名空间共用, 避免各处各自解析导致口径漂移。
 */
@Component
@RequiredArgsConstructor
public class ChatClientProvider {

    /** 模型名不可得时的占位标签(仅用于对话日志与缓存键命名空间) */
    private static final String UNKNOWN_MODEL = "unknown";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AppProperties appProperties;
    private volatile ChatClient cached;

    /**
     * 解析当前实际生效的对话模型名(单一事实来源)：优先取 ChatModel 默认选项中的模型,
     * 不可得时回退 {@code app.chat.model-label} 配置。
     *
     * <p>消费方: 对话日志 {@code chat_log.model_name}(避免与实际模型漂移)、语义缓存键
     * (切换模型后旧模型的回答不再被命中)。
     *
     * @return 模型名; 两处都取不到时返回 "unknown"
     */
    public String modelLabel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model != null && model.getDefaultOptions() != null) {
            String name = model.getDefaultOptions().getModel();
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }
        String configured = appProperties.getChat().getModelLabel();
        return configured == null || configured.isBlank() ? UNKNOWN_MODEL : configured.trim();
    }

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
