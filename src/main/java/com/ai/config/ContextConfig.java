package com.ai.config;

import com.ai.common.JtokTokenCounter;
import com.ai.common.TokenCounter;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文处理管线装配。
 *
 * <p>默认提供 {@link JtokTokenCounter}(jtokkit CL100K_BASE 精确计数)；
 * 如需替换(如接入 qwen 官方分词器或回退启发式估算), 声明同类型 Bean 即可覆盖
 * (@ConditionalOnMissingBean)。
 */
@Configuration
public class ContextConfig {

    /**
     * 默认 Token 计量器(jtokkit 精确计数, CL100K_BASE 对 qwen 分词器为近似但优于启发式估算)。
     *
     * @return TokenCounter 实现
     */
    @Bean
    @ConditionalOnMissingBean(TokenCounter.class)
    public TokenCounter tokenCounter() {
        return new JtokTokenCounter();
    }

    /**
     * 嵌入分批策略: 按条数 ≤20 分批(DashScope 兼容接口硬限), 覆盖 Spring AI 默认按 token 分批——
     * 默认策略只看 token 数, 一个文档切出几十个小块时整批一次发过去会被接口拒 400
     * ({@code batch size is invalid})。Spring AI 的 QdrantVectorStoreAutoConfiguration 里
     * {@code batchingStrategy()} 是 {@code @Bean @ConditionalOnMissingBean}, 声明同类型 Bean 即覆盖。
     *
     * @return 按条数分批策略
     */
    @Bean
    @ConditionalOnMissingBean(BatchingStrategy.class)
    public BatchingStrategy batchingStrategy() {
        return new CountBatchingStrategy();
    }
}
