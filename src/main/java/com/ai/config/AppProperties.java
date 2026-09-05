package com.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务自定义配置(app.*)。字段与 application.yaml 对应。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Chat chat = new Chat();

    @Data
    public static class Rag {
        /** 向量集合名 */
        private String collectionName = "rag_knowledge_base";
        /** 检索 Top-K */
        private int topK = 5;
        /** 相似度阈值 */
        private double similarityThreshold = 0.6;
        /** 注入上下文字符上限 */
        private int contextMaxChars = 8000;
        /** memory | qdrant */
        private String vectorStore = "memory";
    }

    @Data
    public static class Storage {
        /** 上传文件本地存储根目录 */
        private String path = "./data/files";
    }

    @Data
    public static class Chat {
        /** 记录到 chat_log 的模型名标签 */
        private String modelLabel = "qwen-plus";
    }
}
