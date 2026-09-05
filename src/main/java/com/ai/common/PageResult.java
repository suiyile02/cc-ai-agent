package com.ai.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 统一分页结果。
 *
 * @param records   当前页数据
 * @param total     总记录数
 * @param page      页码（从 1 开始）
 * @param size      每页大小
 * @param totalPages 总页数
 */
public record PageResult<T>(List<T> records, long total, int page, int size, int totalPages) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new PageResult<>(records, total, page, size, totalPages);
    }

    public static <T> PageResult<T> from(Page<?> page, List<T> mapped) {
        return of(mapped, page.getTotalElements(), page.getNumber() + 1, page.getSize());
    }
}
