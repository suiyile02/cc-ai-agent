package com.ai.config;

import com.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 安全配置启动校验：prod 下 JWT 密钥缺失/过短/带占位符特征, 或令牌黑名单处于 fail-open 时拒绝启动。
 *
 * <p>仓库内不再提供任何 JWT 默认密钥（默认密钥随源码分发＝任何人可伪造管理员 token）；
 * 生产必须在启动期强制暴露这类问题, 而不是运行期依赖人工检查。
 * 同理, 黑名单 fail-open 会让"Redis 故障"变成"注销功能整体失效"且无任何报错, 故 prod 下一律拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityConfigValidator implements ApplicationRunner {

    /** 最短密钥字节数(HS256 要求 ≥256 bit) */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 占位符特征: 命中即视为"忘了换成真密钥"。
     * 用子串而非具体值, 是为了不把历史上公开过的那个默认串再写回仓库。
     */
    private static final String[] PLACEHOLDER_MARKERS = {"change-me", "changeme", "dev-secret", "your-", "replace-me"};

    private final AppProperties appProperties;
    private final Environment environment;

    /**
     * 启动后立即校验(失败抛异常终止启动)。
     *
     * @param args 启动参数(未使用)
     * @throws IllegalStateException prod 下密钥不合规, 或黑名单为 fail-open
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!environment.matchesProfiles("prod")) {
            // 非生产: JWT_SECRET 留空由 JwtTokenProvider 生成一次性随机密钥(构造时已 WARN)
            return;
        }
        String secret = appProperties.getAuth().getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "生产环境拒绝启动: JWT_SECRET 未注入(仓库已不提供默认密钥)。"
                            + "请设置 ≥32 字节的随机密钥, 例: openssl rand -base64 48");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("生产环境拒绝启动: JWT_SECRET 长度不足 32 字节");
        }
        if (looksLikePlaceholder(secret)) {
            throw new IllegalStateException("生产环境拒绝启动: JWT_SECRET 仍是占位符("
                    + "命中特征 change-me/changeme/dev-secret/your-/replace-me), 请换成真实随机密钥");
        }
        if (appProperties.getAuth().isBlacklistFailOpen()) {
            throw new IllegalStateException(
                    "生产环境拒绝启动: app.auth.blacklist-fail-open 必须为 false"
                            + "(现值 true=Redis 异常时放行已注销令牌)。application-prod.yaml 已设为 false,"
                            + " 请检查是否被环境变量或外部配置覆盖");
        }
        log.info("安全配置校验通过: JWT_SECRET 合规(非占位符且长度达标), 令牌黑名单为 fail-closed");
    }

    /** 是否命中占位符特征(忽略大小写)——用于拦截"直接把示例值当生产密钥"的情况。 */
    private static boolean looksLikePlaceholder(String secret) {
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
