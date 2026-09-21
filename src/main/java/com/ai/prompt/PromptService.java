package com.ai.prompt;

import com.ai.config.AppProperties;
import com.ai.session.SessionType;
import com.ai.rag.RagMode;
import lombok.RequiredArgsConstructor;
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
 *   <li>AGENT 会话      → base-system.st(工具 Agent 角色, 不受 kb-only 影响)</li>
 *   <li>GENERAL / TOOL  → 宽松=general-system.st(自由问答)；严格=kb-only-system.st</li>
 *   <li>KB 意图且有命中 → 系统提示 + rag-context.st(注入资料)</li>
 *   <li>KB 意图但未命中 → 系统提示(要求严格"知识库未找到"，避免编造)</li>
 * </ul>
 *
 * <p>{@code app.chat.kb-only=true} 时"系统提示"一律换成 {@code prompts/kb-only-system.st}
 * (只依据资料/工具结果作答, 否则按固定口径友好拒答), 且资料块的补充规则由"可补充常识"换成
 * "禁止引入资料之外的知识"。<b>该开关是提示词级软约束</b>——模型仍被调用, 不保证零编造,
 * 理由与硬闸门替代方案见 {@code AppProperties.Chat#kbOnly} 与 AGENTS.md。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    /** 严格模式系统提示 */
    private static final String KB_ONLY_SYSTEM = "prompts/kb-only-system.st";
    /** 宽松模式下资料块的补充规则(与改动前的固定文案一致) */
    private static final String LOOSE_EXTRA_RULE = "可适当补充常识性解释";
    /** 严格模式下资料块的补充规则: 与"可补充常识"互斥, 同时出现即自相矛盾 */
    private static final String STRICT_EXTRA_RULE = "只能使用以上资料中的信息，禁止引入资料之外的知识";

    private final AppProperties appProperties;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 按会话类型/意图路由/命中情况选择并组装系统提示词。
     *
     * @param type        会话类型 RAG/AGENT/HYBRID
     * @param mode        意图路由结果 KB/TOOL/GENERAL
     * @param hasContext  是否有检索上下文可注入
     * @param contextText 检索得到的上下文文本
     * @return 组装后的 system 提示词
     */
    public String systemFor(SessionType type, RagMode mode, boolean hasContext, String contextText) {
        if (type == SessionType.AGENT) {
            return baseSystem(type);
        }
        boolean strict = appProperties.getChat().isKbOnly();
        // TOOL(工具类)与 GENERAL(闲聊)都不注入知识库上下文——
        // 工具由 Spring AI 的 ChatClient.tools() 独立注入, 模型会自主决定调用。
        // 严格模式下 TOOL 同样必须换模板: 旧模板"可回答常识科普闲聊"会把"保留内部工具"
        // 变成"工具 + 自由知识", 使开关形同虚设。
        if (mode == RagMode.GENERAL || mode == RagMode.TOOL) {
            return strict ? load(KB_ONLY_SYSTEM) : load("prompts/general-system.st");
        }
        String system = strict ? load(KB_ONLY_SYSTEM) : baseSystem(type);
        if (hasContext && contextText != null && !contextText.isBlank()) {
            String ctx = load("prompts/rag-context.st")
                    .replace("{{context}}", contextText)
                    .replace("{{extraRule}}", strict ? STRICT_EXTRA_RULE : LOOSE_EXTRA_RULE);
            return system + "\n\n" + ctx;
        }
        return system;
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
