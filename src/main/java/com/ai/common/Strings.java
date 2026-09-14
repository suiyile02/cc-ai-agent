package com.ai.common;

/**
 * 字符串工具：截断(字符安全, 不切开 UTF-16 代理对)。
 */
public final class Strings {

    private Strings() {
    }

    /**
     * 截断文本到指定字符数(避免切在增补字符/emoji 的代理对中间)。
     *
     * @param text     原始文本(可空)
     * @param maxChars 最大字符数
     * @return 截断后文本; 原文不超长或为 null 时原样返回
     */
    public static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 0) {
            return "";
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
