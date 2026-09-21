package com.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisReadinessProbe} 单元测试：Redis 可用/不可用两条路径都必须**只打日志不外抛**，
 * 且降级时必须把"哪些能力降级了"逐项说清（这是本探针存在的全部价值）。
 */
class RedisReadinessProbeTest {

    private StringRedisTemplate redis;
    private AppProperties appProperties;
    private Environment environment;
    private RedisReadinessProbe probe;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        appProperties = new AppProperties();
        environment = mock(Environment.class);
        lenient().when(environment.getProperty(anyString(), anyString())).thenReturn("redis");
        probe = new RedisReadinessProbe(redis, appProperties, environment);
        logger = (Logger) LoggerFactory.getLogger(RedisReadinessProbe.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private String logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void redisUpLogsPassingSummary() {
        when(redis.hasKey(anyString())).thenReturn(false);

        assertDoesNotThrow(() -> probe.run(null));

        assertTrue(logs().contains("Redis 自检通过"), logs());
        assertTrue(logs().contains("登录限流后端=redis"), logs());
    }

    @Test
    void redisDownNeverThrowsAndListsDegradedCapabilities() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("Unable to connect to Redis"));

        assertDoesNotThrow(() -> probe.run(null));

        String out = logs();
        assertTrue(out.contains("Redis 自检失败"), out);
        assertTrue(out.contains("Unable to connect to Redis"), out);
        // 逐项降级说明必须齐全, 否则运维仍不知道失去了什么
        assertTrue(out.contains("登录限流"), out);
        assertTrue(out.contains("语义缓存"), out);
        assertFalse(out.contains("意图"), "意图路由结果缓存已随 P3-7 B 批下线, 不得再声称被降级: " + out);
        assertTrue(out.contains("会话缓存"), out);
        assertTrue(out.contains("令牌黑名单"), out);
    }

    @Test
    void failClosedBlacklistReportedAsRejecting() {
        appProperties.getAuth().setBlacklistFailOpen(false);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("down"));

        probe.run(null);

        assertTrue(logs().contains("拒绝(需重新登录)"), logs());
    }

    @Test
    void disabledCachesOmittedFromDegradedList() {
        appProperties.getSemanticCache().setEnabled(false);
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("down"));

        probe.run(null);

        String out = logs();
        assertTrue(out.contains("会话缓存"), out);
        assertTrue(!out.contains("语义缓存→"), "关闭的能力不应声称被降级: " + out);
    }
}
