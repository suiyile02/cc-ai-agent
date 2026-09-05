package com.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson ObjectMapper。
 *
 * <p>说明：Boot 4 将 Jackson 拆为独立 starter 且不默认暴露 ObjectMapper Bean，
 * 这里显式注册(带 Java 8 时间模块)，供 AOP 工具日志与对话日志 JSON 序列化使用。
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
