package com.ai.chat.service;

import com.ai.rag.SourceVO;

import java.util.List;

/**
 * 来源文档名提取与"未找到"判定(审计与缓存口径)。
 *
 * <p>引用来源只落库 {@code chat_log.sources} 与语义缓存, 不再返回前端(2026-09 契约变更)。
 */
public final class ChatSourceDisplay {

    /**
     * 语义负缓存命中时的固定回答。
     *
     * <p>与严格模式提示词 {@code prompts/kb-only-system.st} 要求模型复述的话术逐字一致, 否则用户会
     * 看到两种不同的"没找到"。两者不能共享常量(prompt 模块禁止依赖 chat 模块), 该一致性由
     * {@code PromptServiceTest#strictRefusalSentenceMatchesNegativeCacheConstant} 把守——改这里必须同改模板。
     */
    public static final String NO_RESULT_ANSWER = "知识库中未找到相关信息，请确认问题或补充相关资料后重试。";

    private ChatSourceDisplay() {
    }

    /**
     * 从命中文档提取来源文档名(去重保序 + 去掉扩展名)。
     *
     * @param sources 命中来源列表
     * @return 文档名列表(不含扩展名)
     */
    public static List<String> sourceNames(List<SourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .map(SourceVO::fileName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .map(ChatSourceDisplay::stripExtension)
                .toList();
    }

    /**
     * 去掉文件名末尾的扩展名("员工手册示例.md" → "员工手册示例")。
     *
     * @param name 文件名
     * @return 去扩展名的名称
     */
    public static String stripExtension(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 判断模型回答是否声明"知识库未找到"(命中时不该写正缓存——"未找到"不是可复用答案)。
     *
     * @param answer 回答全文
     * @return true=回答声明未找到相关信息
     */
    public static boolean declaresNoResult(String answer) {
        if (answer == null) {
            return false;
        }
        return answer.contains("知识库中未找到") || answer.contains("未找到相关信息")
                || answer.contains("未找到相关资料") || answer.contains("知识库未找到")
                || answer.contains("未检索到相关");
    }
}
