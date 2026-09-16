package com.ai.user.security;

/**
 * 登录失败限流契约(P0-2/P3)：按"用户名"与"来源 IP"双维度计数, 连续失败达到阈值后锁定。
 *
 * <p>双实现按 {@code app.auth.rate-limit-backend} 选择:
 * {@code redis}(默认, 多实例统一计数) / {@code memory}(单实例进程内, 测试友好)。
 */
public interface LoginAttemptLimiter {

    /**
     * 判定是否被锁定(用户名或 IP 任一维度命中即锁定)。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return true=锁定期内, 应直接拒绝登录
     */
    boolean isLocked(String username, String ip);

    /**
     * 记录一次失败(两维度各计一次), 达阈值进入锁定。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    void recordFailure(String username, String ip);

    /**
     * 登录成功: 清除该用户名与该 IP 的失败计数。
     *
     * @param username 登录名
     * @param ip       来源 IP
     */
    void recordSuccess(String username, String ip);

    /**
     * 剩余锁定毫秒数(供日志/响应提示), 未锁定返回 0。
     *
     * @param username 登录名
     * @param ip       来源 IP
     * @return 剩余锁定毫秒
     */
    long remainingLockMs(String username, String ip);
}
