package com.ai.config.props;

import lombok.Data;

/** 鉴权与令牌({@code app.auth.*}), 从 {@code AppProperties.Auth} 迁出。 */
@Data
public class AuthProps {

    /**
     * JWT 签名密钥。仓库内不提供任何默认值：留空时非生产自动生成一次性随机密钥,
     * 生产由 SecurityConfigValidator 拒绝启动。生产经 JWT_SECRET 环境变量注入(≥32字节)。
     */
    private String jwtSecret = "";
    /** Token 有效期(小时) */
    private long tokenExpireHours = 168;
    /**
     * 开发便捷开关(默认关闭, **仓库内不提供任何打开它的配置文件**): true 时允许用 X-User-Id
     * 请求头模拟登录(未携带 Token 时)。本机调试需要时显式传 --app.auth.dev-user-header-enabled=true;
     * 生产环境必须保持 false, 否则存在身份伪造风险。
     */
    private boolean devUserHeaderEnabled = false;
    /**
     * 令牌黑名单降级策略: true=Redis 异常时放行(保可用), false=拒绝(保安全, 生产建议)。
     */
    private boolean blacklistFailOpen = true;

    /**
     * 【无字段——刻意留空】{@code app.auth.rate-limit-backend}(redis|memory)不绑定到本类:
     * 它是<b>装配期</b>开关, 由 {@code @ConditionalOnProperty} 在两个
     * {@code LoginAttemptLimiter} 实现之间二选一, 并直接被 RedisReadinessProbe 读原文。
     *
     * <p>条件装配发生在 bean 创建之前, 开个字段既来不及参与决策又是"假旋钮"; 因聚合根开了严格绑定,
     * 该键必须在 {@code AppProperties#EXEMPT_UNKNOWN_KEYS} 登记豁免(可审清单, 而非静默忽略)。
     */
}
