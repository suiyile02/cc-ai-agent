package com.ai.user.dto;

import java.time.LocalDateTime;

/**
 * 用户信息 VO。
 *
 * @param id        用户 ID
 * @param username  登录名
 * @param nickname  昵称
 * @param role      角色(ADMIN/USER)
 * @param status    状态(1 正常 / 0 禁用)
 * @param createdAt 创建时间
 */
public record UserVO(Long id, String username, String nickname, String role, Integer status,
                     LocalDateTime createdAt) {
}
