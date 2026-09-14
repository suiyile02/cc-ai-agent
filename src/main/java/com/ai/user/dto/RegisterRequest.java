package com.ai.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 *
 * @param username 登录名(3~32 位字母/数字/下划线)
 * @param password 密码(6~64 位)
 * @param nickname 昵称(可选, ≤64)
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{3,32}$", message = "用户名需为3~32位字母/数字/下划线")
        String username,
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在6~64之间")
        String password,
        @Size(max = 64, message = "昵称长度不能超过64")
        String nickname) {

    /**
     * 归一化：昵称为空时回退为用户名。
     *
     * @return 展示昵称(非空)
     */
    public String effectiveNickname() {
        return (nickname == null || nickname.isBlank()) ? username : nickname.trim();
    }
}
