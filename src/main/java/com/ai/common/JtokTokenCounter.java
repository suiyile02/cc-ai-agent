package com.ai.common;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 基于 jtokkit(CL100K_BASE)的精确 Token 计数实现。
 *
 * <p>替代 {@link HeuristicTokenCounter} 的字符估算：count 与 truncateToTokens 均按真实
 * BPE 词元计算——截断不再是估算迭代，而是"编码 → 取前 maxTokens 个词元 → 解码"的精确还原。
 * 注意：CL100K_BASE 是 OpenAI 系分词器，对 qwen 等模型的私有分词器为近似（中文常见字约
 * 1~2 词元/字），方向整体偏保守(计数偏高)，用于预算控制安全。
 *
 * <p>{@link Encoding} 实例加载成本高且线程安全，按单例缓存。
 */
public class JtokTokenCounter implements TokenCounter {

    /** 每条消息的固定开销(角色标记/分隔符), 与 HeuristicTokenCounter 口径一致 */
    private static final int MESSAGE_OVERHEAD = 4;

    private final Encoding encoding;

    public JtokTokenCounter() {
        this(Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE));
    }

    /** 测试/扩展用构造器: 允许指定分词器编码 */
    public JtokTokenCounter(Encoding encoding) {
        this.encoding = encoding;
    }

    /**
     * 精确计算文本 token 数。
     *
     * @param text 文本(可为 null)
     * @return token 数, null/空串返回 0
     */
    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 精确计算消息列表 token 数(含每条消息固定开销)。
     *
     * @param messages 消息列表(可为 null)
     * @return token 数, null/空列表返回 0
     */
    @Override
    public int count(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            total += count(message.getText()) + MESSAGE_OVERHEAD;
        }
        return total;
    }

    /**
     * 按 token 精确截断文本：编码后取前 maxTokens 个词元并解码还原，
     * 结果的 token 数必然 ≤ maxTokens(相比接口 default 的估算迭代更精确)。
     *
     * @param text      原始文本
     * @param maxTokens token 上限
     * @return 截断后文本; null 返回 null, maxTokens≤0 返回空串, 预算内原样返回
     */
    @Override
    public String truncateToTokens(String text, int maxTokens) {
        if (text == null) {
            return null;
        }
        if (text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return text;
        }
        IntArrayList kept = new IntArrayList();
        for (int i = 0; i < maxTokens; i++) {
            kept.add(tokens.get(i));
        }
        return encoding.decode(kept);
    }
}
