package com.ai.config;

import com.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 安全配置启动校验：prod profile 下使用默认 JWT 密钥、或令牌黑名单处于 fail-open 时拒绝启动。
 *
 * <p>默认密钥随代码仓库分发, 任何拿到源码的人都能伪造管理员 token——
 * 这是 P0 级安全风险, 必须在启动期强制暴露而非运行期依赖人工检查。
 * 同理, 黑名单 fail-open 会让"Redis 故障"变成"注销功能整体失效"且无任何报错,
 * 因此 prod 下也必须在启动期拒绝, 而不是等运维发现。
 */
@Slf4j
@Component
public class SecurityConfigValidator implements ApplicationRunner {

    /** application.yaml 中的 JWT 默认(开发)密钥, prod 下出现即拒绝启动 */
    static final String DEV_JWT_SECRET = "ai-agent-dev-secret-change-me-in-prod-2026";

    private final AppProperties appProperties;
    private final org.springframework.core.env.Environment environment;

    public SecurityConfigValidator(AppProperties appProperties,
            org.springframework.core.env.Environment environment) {
        this.appProperties = appProperties;
        this.environment = environment;
    }

    /**
     * 启动后立即校验(失败抛异常终止启动)。
     *
     * @param args 启动参数(未使用)
     * @throws IllegalStateException prod 下密钥为默认值/长度不足
     */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        boolean prod = environment.matchesProfiles("prod");
        String secret = appProperties.getAuth().getJwtSecret();

        if (!prod) {
            if (DEV_JWT_SECRET.equals(secret)) {
                log.warn("当前使用默认 JWT 开发密钥(仅限本地开发); 生产必须通过 JWT_SECRET 环境变量注入 ≥32 字节随机串");
            }
            return;
        }
        if (DEV_JWT_SECRET.equals(secret) || secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "生产环境拒绝启动: JWT_SECRET 未注入或仍为默认开发密钥。请设置环境变量 JWT_SECRET(≥32 字节随机串)");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("生产环境拒绝启动: JWT_SECRET 长度不足 32 字节");
        }
        // 令牌黑名单在 prod 必须 fail-closed: 否则 Redis 一挂, 注销/吊销等于没做
        if (appProperties.getAuth().isBlacklistFailOpen()) {
            throw new IllegalStateException(
                    "生产环境拒绝启动: app.auth.blacklist-fail-open 必须为 false"
                            + "(现值 true=Redis 异常时放行已注销令牌)。application-prod.yaml 已设为 false,"
                            + " 请检查是否被环境变量或外部配置覆盖");
        }
        log.info("安全配置校验通过: JWT_SECRET 已自定义且长度合规, 令牌黑名单为 fail-closed");
    }
}
