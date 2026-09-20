package com.ai.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SecurityConfigValidator} 单元测试：prod 下的三条硬规则(缺失/过短/占位符特征)
 * 与黑名单 fail-closed 要求必须拒绝启动; 非 prod 必须放行(否则本地无法启动)。
 */
class SecurityConfigValidatorTest {

    /** 合规的生产密钥(≥32 字节且不含占位符特征) */
    private static final String GOOD_SECRET = "0123456789abcdef0123456789abcdef0123456789";

    private AppProperties appProperties;
    private Environment environment;
    private SecurityConfigValidator validator;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        environment = mock(Environment.class);
        lenient().when(environment.matchesProfiles("prod")).thenReturn(false);
        validator = new SecurityConfigValidator(appProperties, environment);
    }

    /** 置为 prod 并给出一套合规基线配置 */
    private void givenProd() {
        when(environment.matchesProfiles("prod")).thenReturn(true);
        appProperties.getAuth().setJwtSecret(GOOD_SECRET);
        appProperties.getAuth().setBlacklistFailOpen(false);
    }

    @Test
    void prodAcceptsHardenedBaseline() {
        givenProd();

        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void prodRejectsMissingSecret() {
        givenProd();
        appProperties.getAuth().setJwtSecret("");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
    }

    @Test
    void prodRejectsShortSecret() {
        givenProd();
        appProperties.getAuth().setJwtSecret("short-secret");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("32 字节"), e.getMessage());
    }

    /** 历史上公开过的示例值不得被当作生产密钥沿用(用特征匹配, 不把那个串再写回仓库) */
    @Test
    void prodRejectsPlaceholderLikeSecret() {
        givenProd();
        appProperties.getAuth().setJwtSecret("ai-agent-dev-secret-CHANGE-ME-in-prod-2026-abcdefgh");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("占位符"), e.getMessage());
    }

    /** 黑名单 fail-open 在 prod 等于"Redis 一挂注销就静默失效", 必须拒绝启动 */
    @Test
    void prodRejectsBlacklistFailOpen() {
        givenProd();
        appProperties.getAuth().setBlacklistFailOpen(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("blacklist-fail-open"), e.getMessage());
    }

    @Test
    void nonProdToleratesEmptySecret() {
        assertTrue(appProperties.getAuth().getJwtSecret().isEmpty(), "仓库默认值应为空");
        assertDoesNotThrow(() -> validator.run(null), "开发环境留空即可(由 JwtTokenProvider 随机兜底)");
    }
}
