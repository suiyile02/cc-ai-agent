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
 * {@link SecurityConfigValidator} 单元测试：prod 下的三条硬规则(默认密钥/短密钥/黑名单 fail-open)
 * 必须拒绝启动, 非 prod 必须放行(否则本地开发无法启动)。
 */
class SecurityConfigValidatorTest {

    /** 合规的生产密钥(≥32 字节) */
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
    void prodRejectsDefaultJwtSecret() {
        givenProd();
        appProperties.getAuth().setJwtSecret(SecurityConfigValidator.DEV_JWT_SECRET);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
    }

    @Test
    void prodRejectsShortJwtSecret() {
        givenProd();
        appProperties.getAuth().setJwtSecret("short-secret");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("32 字节"), e.getMessage());
    }

    /** 本次新增规则: prod 下黑名单 fail-open 等于"Redis 一挂注销就静默失效", 必须拒绝启动 */
    @Test
    void prodRejectsBlacklistFailOpen() {
        givenProd();
        appProperties.getAuth().setBlacklistFailOpen(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(e.getMessage().contains("blacklist-fail-open"), e.getMessage());
    }

    @Test
    void nonProdToleratesDevDefaults() {
        assertDoesNotThrow(() -> validator.run(null), "开发环境必须能用默认配置直接启动");
    }
}
