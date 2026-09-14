package com.ai.common;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 启发式 Token 估算器(零外部依赖, 按字符分类估算)。
 *
 * <p>估算规则：CJK(中日韩)字符每字约 1 token, 其余字符按 {@code ceil(len/4)} 估算;
 * 每条消息额外计 {@link #MESSAGE_OVERHEAD} token(角色标记/分隔符开销)。
 *
 * <p>【已被替代】生产默认实现已切换为 {@link JtokTokenCounter}(jtokkit CL100K_BASE 精确计数,
 * 见 ContextConfig#tokenCounter)。本类保留用途仅两个:
 * ① 单元测试构造 TokenCounter(避免测试依赖分词器词表); ② 需要零依赖估算时的回退实现。
 * 新功能请勿再引用本类, 使用 TokenCounter 接口注入即可。
 */
public class HeuristicTokenCounter implements TokenCounter {

    /** 每条消息的固定开销(角色/分隔符) */
    private static final int MESSAGE_OVERHEAD = 4;

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本(可为 null)
     * @return token 估算值, null/空串返回 0
     */
    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    /**
     * 估算一组消息的 token 数(含每条消息固定开销)。
     *
     * @param messages 消息列表(可为 null)
     * @return token 估算值, null/空列表返回 0
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
     * 判断字符是否属于 CJK(含中文标点/全角/日文假名/韩文)范围。
     *
     * @param c 字符
     * @return true=CJK 字符
     */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // CJK 扩展 A
                || (c >= 0x3000 && c <= 0x303F) // CJK 符号与标点
                || (c >= 0xFF00 && c <= 0xFFEF) // 全角字符
                || (c >= 0x3040 && c <= 0x30FF) // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文音节
    }
}
