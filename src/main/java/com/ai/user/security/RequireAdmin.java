package com.ai.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 授权注解：要求当前登录用户具备管理员角色(ADMIN)。
 *
 * <p>由 {@link SelfOrAdminAspect#enforceAdmin} 处理：非管理员直接拒绝
 * ({@code AUTH_FAILED}/HTTP 403)。用于"管理动作"类操作——如知识库文档的删除/重处理
 * (影响全公司共享的 RAG 内容, 不能交给任意登录用户)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
