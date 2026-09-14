package com.ai.common;

import java.time.LocalDate;

/**
 * 日期参数解析工具：把接口层 "yyyy-MM-dd" 字符串安全转换为 LocalDate。
 */
public final class DateParamUtils {

    private DateParamUtils() {
    }

    /**
     * 解析日期参数。
     *
     * @param value 原始字符串，形如 "2026-09-05"；null 或空白返回 null(表示不过滤)
     * @return 解析后的 LocalDate；格式非法时抛出业务异常 PARAM_ERROR
     * @throws BusinessException 参数格式错误
     */
    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "日期参数格式应为 yyyy-MM-dd：" + value);
        }
    }
}
