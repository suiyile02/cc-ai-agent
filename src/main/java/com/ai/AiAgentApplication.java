package com.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 智能问答 Agent 入口。
 *
 * <p>模块：知识库管理 / 智能对话(RAG) / Agent 工具 / 会话管理 / 系统日志 / 异常与降级。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }

}
