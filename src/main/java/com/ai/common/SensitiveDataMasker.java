package com.ai.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具：对落库日志中的手机号/邮箱打码(P2-3)。
 *
 * <p>手机号保留前 3 后 2(138****56)，邮箱保留首字符与域名(a***@demo.com)。
 * 用于工具调用日志等可能携带用户真实联系方式的持久化内容。
 */
public final class SensitiveDataMasker {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._+-]+)@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    private SensitiveDataMasker() {
    }

    /**
     * 脱敏文本中的手机号与邮箱(其余内容原样保留)。
     *
     * @param text 原始文本(可空)
     * @return 脱敏后文本
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = PHONE.matcher(text).replaceAll(m ->
                m.group().substring(0, 3) + "****" + m.group().substring(7));
        out = EMAIL.matcher(out).replaceAll(m ->
                m.group(1).substring(0, 1) + "***@" + m.group(2));
        return out;
    }
}
