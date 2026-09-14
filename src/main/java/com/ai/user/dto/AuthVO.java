package com.ai.user.dto;

/**
 * 登录/注册响应(令牌 + 用户信息)。
 *
 * @param token JWT 令牌(后续请求放入 Authorization: Bearer {token})
 * @param user  当前用户信息
 */
public record AuthVO(String token, UserVO user) {
}
