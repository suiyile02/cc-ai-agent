package com.ai.prompt;

import com.ai.session.entity.ChatSession.SessionType;
import com.ai.rag.RagMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词组装。模板位于 classpath:/prompts/*.st，支持 {{sessionType}} / {{context}} 占位替换。
 *
 * <p>按「会话类型 + 意图路由结果 + 是否命中」选择模板：
 * <ul>
 *   <li>AGENT 会话      → base-system.st(工具 Agent 角色)</li>
 *   <li>GENERAL 意图    → general-system.st(自由问答, 不检索知识库)</li>
 *   <li>KB 意图且有命中 → base-system.st + rag-context.st(注入资料并要求列出参考来源)</li>
 *   <li>KB 意图但未命中 → base-system.st(严格“知识库未找到”，避免编造)</li>
 * </ul>
 */
@Slf4j
@Service
public class PromptService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 按会话类型/意图路由/命中情况选择并组装系统提示词。
     *
     * @param type        会话类型 RAG/AGENT/HYBRID
     * @param mode        意图路由结果 KB/GENERAL
     * @param hasContext  是否有检索上下文可注入
     * @param contextText 检索得到的上下文文本
     * @return 组装后的 system 提示词
     */
    public String systemFor(SessionType type, RagMode mode, boolean hasContext, String contextText) {
        if (type == SessionType.AGENT) {
            return baseSystem(type);
        }
        if (mode == RagMode.GENERAL) {
            return load("prompts/general-system.st");
        }
        if (hasContext && contextText != null && !contextText.isBlank()) {
            String ctx = load("prompts/rag-context.st").replace("{{context}}", contextText);
            return baseSystem(type) + "\n\n" + ctx;
        }
        return baseSystem(type);
    }

    /**
     * 加载基础系统提示词并替换会话类型占位符。
     *
     * @param type 会话类型
     * @return base-system.st 内容(带会话类型)
     */
    private String baseSystem(SessionType type) {
        return load("prompts/base-system.st").replace("{{sessionType}}", type.name());
    }

    /**
     * 读取指定模板原文(带缓存), 供上下文管线的改写器/摘要器复用占位符替换。
     *
     * @param path 资源路径, 如 "prompts/query-rewrite.st"
     * @return 模板文本；读取失败返回空串
     */
    public String template(String path) {
        return load(path);
    }

    /**
     * 从 classpath 读取模板(带缓存；读取失败不缓存空串, 下次调用重试)。
     *
     * @param path 资源路径, 如 "prompts/base-system.st"
     * @return 模板文本；读取失败记录错误并返回空串
     */
    private String load(String path) {
        String cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            cache.put(path, text);
            return text;
        } catch (IOException e) {
            // 失败不入缓存, 避免 IO 抖动一次就永久毒化为空模板
            log.error("加载提示词模板失败: {}", path, e);
            return "";
        }
    }
}
