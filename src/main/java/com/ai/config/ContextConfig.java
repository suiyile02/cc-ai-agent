package com.ai.config;

import com.ai.common.JtokTokenCounter;
import com.ai.common.TokenCounter;
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
}
