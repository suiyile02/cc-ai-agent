package com.ai.user.security;

import com.ai.common.BusinessException;
import com.ai.common.ErrorCode;

/**
 * 当前登录用户上下文(ThreadLocal, 由 AuthInterceptor 在请求前写入、请求后清理)。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    /** 普通用户角色标识 */
    public static final String ROLE_USER = "USER";

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    /**
     * 演示账号角色标识(2026-10-13 新增)：介于 USER 与 ADMIN 之间的受限演示身份——
     * 知识库可查看全文+删除文档({@code RequireRoles} 声明), 系统日志只看本人对话日志
     * (既有 {@code RequireSelfOrAdmin} 自动收敛到本人); 上传/重处理/全库重建等仍管理员专属。
     * 由注册后手工 {@code UPDATE sys_user SET role='DEMO'} 赋予(无自助入口, 防演示站被批量注册提权)。
     */
    public static final String ROLE_DEMO = "DEMO";

    /**
     * 当前用户载体。
     *
     * @param userId   用户 ID
     * @param username 用户名(可能为空, 如 X-User-Id 开发模式)
     * @param role     角色(ADMIN/DEMO/USER)
     */
    public record CurrentUser(Long userId, String username, String role) {

        /**
         * 是否管理员。
         *
         * @return true=ADMIN 角色
         */
        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }

        /**
         * 是否演示账号。
         *
         * @return true=DEMO 角色
         */
        public boolean isDemo() {
            return ROLE_DEMO.equals(role);
        }
    }

    /**
     * 写入当前用户。
     *
     * @param user 当前用户
     */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 读取当前用户(可能为空, 未登录)。
     *
     * @return 当前用户或 null
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 清理上下文(请求结束必须调用, 防止线程池复用串号)。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 读取当前用户 ID。
     *
     * @return 用户 ID, 未登录返回 null
     *
     * <p>【仅同类内引用】唯一调用方是同类 {@link #requireUserId()}; 无外部调用点,
     * 可降级为 private(保留 public 是为兼容未来可空读取场景)。
     */
    public static Long currentUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.userId();
    }

    /**
     * 获取当前用户 ID, 未登录抛业务异常。
     *
     * @return 用户 ID
     * @throws BusinessException TOKEN_INVALID
     */
    public static Long requireUserId() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "未登录或凭证已失效");
        }
        return userId;
    }
}
