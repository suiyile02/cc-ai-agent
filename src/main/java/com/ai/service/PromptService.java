package com.ai.service;

import com.ai.entity.ChatSession.SessionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 提示词组装。模板位于 classpath:/prompts/*.st, 支持 {{sessionType}} / {{context}} 占位替换。
 */
@Slf4j
@Service
public class PromptService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String systemFor(SessionType type, boolean hasContext, String contextText) {
        String base = load("prompts/base-system.st");
        String system = base.replace("{{sessionType}}", type.name());

        boolean useRag = type == SessionType.RAG || type == SessionType.HYBRID;
        if (useRag && hasContext && contextText != null && !contextText.isBlank()) {
            String ctx = load("prompts/rag-context.st").replace("{{context}}", contextText);
            system = system + "\n\n" + ctx;
        }
        return system;
    }

    private String load(String path) {
        return cache.computeIfAbsent(path, p -> {
            try (InputStream in = new ClassPathResource(p).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("加载提示词模板失败: {}", p, e);
                return "";
            }
        });
    }
}
