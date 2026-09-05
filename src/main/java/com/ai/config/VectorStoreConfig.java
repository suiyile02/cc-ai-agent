package com.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库配置。
 *
 * <ul>
 *   <li>默认 memory 模式：进程内 {@link SimpleVectorStore}(零外部依赖, 数据随进程重启丢失)</li>
 *   <li>qdrant 模式：由 spring-ai-starter-vector-store-qdrant 自动装配外部 Qdrant
 *       (见 application-qdrant.yaml)，本类在该模式下不生效。</li>
 * </ul>
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.rag", name = "vector-store", havingValue = "memory", matchIfMissing = true)
    @ConditionalOnBean(EmbeddingModel.class)
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
