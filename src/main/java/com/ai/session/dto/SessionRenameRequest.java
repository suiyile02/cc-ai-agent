package com.ai.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话改名请求。
 *
 * @param title 新标题(必填, ≤200 字符, 与建会话同口径)
 */
public record SessionRenameRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200") String title) {
}
