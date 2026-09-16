package com.ai.chat.service;

import com.ai.chat.dto.SourceVO;

import java.util.List;

/**
 * 来源展示口径(前端契约)：引用来源只展示**文档名**(去扩展名、去重保序)，
 * 且模型回答声明"知识库未找到"时不展示来源。
 *
 * <p>审计日志(chat_log.sources)保留真实召回记录, 不经过本类过滤。
 */
public final class ChatSourceDisplay {

    /**
     * 语义负缓存命中时的固定回答(与 base-system.st 的"知识库未找到"口径一致,
     * 文本命中 {@link #declaresNoResult(String)} 故来源展示为空)。
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
     * 判断模型回答是否声明"知识库未找到"(声明时前端不应展示参考来源)。
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

    /**
     * 计算前端展示口径的来源列表: 模型声明"未找到"时返回空列表。
     *
     * @param answer   回答全文
     * @param rawNames 真实召回的来源文档名
     * @return 展示用来源列表
     */
    public static List<String> displayedSources(String answer, List<String> rawNames) {
        return declaresNoResult(answer) ? List.of() : rawNames;
    }
}
