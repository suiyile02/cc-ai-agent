package com.ai.common;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Token 计量抽象：为上下文预算/截断提供统一的 token 计量口径。
 *
 * <p>当前默认实现为 {@link JtokTokenCounter}(jtokkit CL100K_BASE 精确计数, 见
 * {@code ContextConfig#tokenCounter}); {@link HeuristicTokenCounter} 为备用估算实现(仅测试引用)。
 * 如需接入其它分词器(如 qwen 官方), 声明同类型 Bean 即可覆盖(@ConditionalOnMissingBean)。
 */
public interface TokenCounter {

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本(可为 null)
     * @return token 估算值, null/空串返回 0
     */
    int count(String text);

    /**
     * 估算一组消息的 token 数(含每条消息的角色/分隔开销)。
     *
     * @param messages 消息列表(可为 null)
     * @return token 估算值, null/空列表返回 0
     */
    int count(List<Message> messages);

    /**
     * 按 token 预算截断文本(从头部保留, 超出部分丢弃)。
     *
     * <p>先按占比估算截断点, 再逐步回收确保不超预算; 文本已在预算内则原样返回。
     *
     * @param text      待截断文本(可为 null)
     * @param maxTokens token 上限(≤0 视为空)
     * @return 截断后的文本; 入参为 null 时返回 null
     */
    /**
     * 按 token 上限截断文本(默认实现: 按字符占比估算切点后循环收缩, 精度有限)。
     *
     * <p>【已被替代】生产实现 JtokTokenCounter 已覆写为本方法(精确编码截断, 见
     * {@link JtokTokenCounter#truncateToTokens}); 此默认实现当前仅被
     * {@code HeuristicTokenCounter}(备用实现, 仅测试引用)间接使用, 生产路径不可达。
     */
    default String truncateToTokens(String text, int maxTokens) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        int total = count(text);
        if (total <= maxTokens) {
            return text;
        }
        int estimated = (int) ((long) text.length() * maxTokens / total);
        String cut = text.substring(0, Math.max(1, Math.min(estimated, text.length())));
        while (cut.length() > 1 && count(cut) > maxTokens) {
            cut = cut.substring(0, (int) (cut.length() * 0.9));
        }
        return cut;
    }
}
