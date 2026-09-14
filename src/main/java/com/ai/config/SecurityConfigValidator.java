package com.ai.config;

import com.ai.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 安全配置启动校验：prod profile 下使用默认 JWT 密钥时拒绝启动。
 *
 * <p>默认密钥随代码仓库分发, 任何拿到源码的人都能伪造管理员 token——
 * 这是 P0 级安全风险, 必须在启动期强制暴露而非运行期依赖人工检查。
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
        log.info("安全配置校验通过: JWT_SECRET 已自定义且长度合规");
    }
}
