package com.ai.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 授权注解：要求当前登录用户是管理员, 或查询范围限定为本人。
 *
 * <p>由 {@link SelfOrAdminAspect} 处理：管理员直接放行; 非管理员时把
 * {@link #userIdParam()} 指定的 Handler 参数强制改写为当前登录用户 ID
 * (不传或传他人 ID 均只能查到本人数据)。数据过滤由 Service 层按 userId 执行。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireSelfOrAdmin {

    /**
     * 需要强制为当前用户的参数名(Handler 方法形参名)。
     *
     * @return 参数名
     */
    String userIdParam();
}
