package com.ai.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 授权注解：要求当前登录用户的角色在声明列表内({@link UserContext} 角色常量)。
 *
 * <p>由 {@code SelfOrAdminAspect#enforceRoles} 处理：角色不匹配直接拒绝
 * ({@code AUTH_FAILED}/HTTP 403)。用于"管理员 + 受限角色共用"的操作——如知识库
 * 文档删除对 ADMIN 与 DEMO(演示账号)开放。纯管理员操作仍用 {@link RequireAdmin}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRoles {

    /**
     * 允许访问的角色列表(取 UserContext.ROLE_* 常量)。
     *
     * @return 角色数组, 空数组等于全部拒绝
     */
    String[] value();
}
