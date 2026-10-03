package com.ai.prompt;

import com.ai.config.AppProperties;
import com.ai.session.SessionType;
import com.ai.rag.ChatOutcome;
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
 * 提示词组装。模板位于 classpath:/prompts/*.st，支持 {{sessionType}} / {{context}} / {{extraRule}} 占位替换。
 *
 * <p>系统提示词由**本轮出口**({@link ChatOutcome})决定，而非由关键词表预判决定——这是 P3-7 的落点：
 * 「知识库内容能否被问到」不再取决于有人记得改词表。模板映射见 {@link #systemFor}。
 *
 * <p>{@code app.chat.kb-only}(默认 **true**) 决定"无知识库依据时是否允许用模型自身知识作答":
 * true → 一律走 {@code prompts/kb-only-system.st}(无据必拒答); false → 走 {@code general-system.st}(自由作答)。
 * 它是提示词级软约束, 不保证零编造, 理由与硬闸门替代见 {@code AppProperties.Chat#kbOnly} 与 AGENTS.md。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    /** 严格模式系统提示 */
    private static final String KB_ONLY_SYSTEM = "prompts/kb-only-system.st";
    /** 严格模式 + 知识库零命中时的工具裁决指令(拼接在 kb-only-system 之后, 固定拒答原文不动) */
    private static final String TOOLS_FALLBACK = "prompts/tools-fallback.st";
    /** 自由问答系统提示(无知识库依据时允许用模型自身知识) */
    private static final String GENERAL_SYSTEM = "prompts/general-system.st";
    /** 宽松模式下资料块的补充规则(与改动前的固定文案一致) */
    private static final String LOOSE_EXTRA_RULE = "可适当补充常识性解释";
    /**
     * 严格模式下资料块的补充规则: 与"可补充常识"互斥, 同时出现即自相矛盾。
     *
     * <p>后半句针对**部分命中**(实测同一模板在"保修期"题会先答已知部分、在"退货地址"题却整题拒答,
     * 行为不稳定)——资料只覆盖问题一部分时必须先答可答部分, 这是真实问答里最常见的形态。
     */
    private static final String STRICT_EXTRA_RULE = "只能使用以上资料中的信息，禁止引入资料之外的知识；"
            + "若资料只覆盖了问题的一部分，先据资料回答那一部分，并单独指出其余部分知识库未涉及";

    private final AppProperties appProperties;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 按会话类型与本轮出口选择并组装系统提示词。
     *
     * <table border="1">
     *   <caption>模板映射</caption>
     *   <tr><th>出口</th><th>kb-only=false</th><th>kb-only=true(默认)</th></tr>
     *   <tr><td>ANSWERED_FROM_KB / ANSWERED_FROM_CACHE</td>
     *       <td>base-system + rag-context(可补充常识)</td><td>kb-only-system + rag-context(禁引入外部知识)</td></tr>
     *   <tr><td>REFUSED_NO_EVIDENCE</td><td colspan="2">kb-only-system(固定口径友好拒答; 该出口只可能在严格模式出现)</td></tr>
     *   <tr><td>TOOL_DATA</td><td>general-system</td><td>kb-only-system(防御分支: 决策时点已不产出)</td></tr>
     *   <tr><td>NO_EVIDENCE_WITH_TOOLS</td><td>general-system</td><td>kb-only-system + tools-fallback(先按工具描述裁决, 无工具可答才固定拒答)</td></tr>
     *   <tr><td>ANSWERED_OPEN</td><td>general-system(自由作答)</td><td>kb-only-system(严格模式统一收口: 未检索轮/降级轮也按固定口径引导, 不自由发挥)</td></tr>
     *   <tr><td>AGENT 会话(任意出口)</td><td colspan="2">base-system——本就靠工具; 严格模式下非"有据"出口已先被收口为 kb-only-system</td></tr>
     * </table>
     *
     * @param type        会话类型 RAG/AGENT/HYBRID
     * @param outcome     本轮出口
     * @param hasContext  是否有检索上下文可注入
     * @param contextText 检索得到的上下文文本
     * @return 组装后的 system 提示词
     */
    public String systemFor(SessionType type, ChatOutcome outcome, boolean hasContext, String contextText) {
        boolean strict = appProperties.getChat().isKbOnly();
        // kbOnly=true: 除"有据作答"(知识库/缓存命中)与"零命中但有工具可裁"外, 一律严格口径收口——
        // 堵住"未检索轮/降级轮反而自由发挥(可能编造业务事实)"的不一致窗口
        if (strict
                && outcome != ChatOutcome.ANSWERED_FROM_KB
                && outcome != ChatOutcome.ANSWERED_FROM_CACHE
                && outcome != ChatOutcome.NO_EVIDENCE_WITH_TOOLS
                && outcome != ChatOutcome.TOOL_DATA) {
            return load(KB_ONLY_SYSTEM);
        }
        if (type == SessionType.AGENT) {
            return baseSystem(type);
        }
        return switch (outcome) {
            // 有知识库依据: 注入资料块。缓存命中路径不装配上下文, 走到这里时 hasContext 必为 true
            case ANSWERED_FROM_KB, ANSWERED_FROM_CACHE -> withContext(strict, type, hasContext, contextText);
            case REFUSED_NO_EVIDENCE -> load(KB_ONLY_SYSTEM);
            // 零命中但有工具可调: 严格口径 + 工具裁决指令(先按工具描述试工具, 无工具可答才固定拒答)
            case NO_EVIDENCE_WITH_TOOLS -> strict
                    ? load(KB_ONLY_SYSTEM) + "\n\n" + load(TOOLS_FALLBACK)
                    : load(GENERAL_SYSTEM);
            // 防御分支: 决策时点已不产出 TOOL_DATA(旧工具轮短路已废除), 保留防外部误传
            case TOOL_DATA -> strict ? load(KB_ONLY_SYSTEM) : load(GENERAL_SYSTEM);
            case ANSWERED_OPEN -> load(GENERAL_SYSTEM);
        };
    }

    /** 有命中时在系统提示后追加资料块; 无命中(理论上不该出现)时退回纯系统提示 */
    private String withContext(boolean strict, SessionType type, boolean hasContext, String contextText) {
        String system = strict ? load(KB_ONLY_SYSTEM) : baseSystem(type);
        if (!hasContext || contextText == null || contextText.isBlank()) {
            return system;
        }
        String ctx = load("prompts/rag-context.st")
                .replace("{{context}}", contextText)
                .replace("{{extraRule}}", strict ? STRICT_EXTRA_RULE : LOOSE_EXTRA_RULE);
        return system + "\n\n" + ctx;
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
