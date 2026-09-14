package com.ai.user.security;

import com.ai.user.dto.LoginRequest;

/**
 * 登录请求上下文：请求体 + 来源 IP(供失败限流按 IP 维度计数)。
 *
 * @param request 登录请求体
 * @param clientIp 客户端 IP
 */
public record LoginContext(LoginRequest request, String clientIp) {
}
