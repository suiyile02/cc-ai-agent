package com.ai.rag.service;

import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.rag.IntentCacheAdmin;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * 意图路由结果缓存装饰器：把"问题 → 路由结果(TOOL/KB/GENERAL)"缓存进 Redis，
 * 归一化后相同的问题直接复用结论。包在真实路由（{@link KeywordIntentRouter}）外层，
 * 消费者注入 {@link IntentRouter} 即拿到带缓存版本，故声明 {@code @Primary}。
 *
 * <p>键 = {@code rag:intent:v{version}:{sha256(归一化问题)}}，TTL 默认 60 分钟兜底旧词表；
 * 改词表后调 {@link #evictAll()} 版本自增立即失效。Redis 异常一律按未命中走真实路由（WARN 节流）。
 */
@Slf4j
@Component
@Primary    //标注为优先实现类（当有多个实现类时，优先选择）
@RequiredArgsConstructor
public class CachingIntentRouter implements IntentRouter, IntentCacheAdmin {

    /** 路由版本号键(调 evictAll 自增) */
    static final String VERSION_KEY = "rag:intent:version";
    private static final String KEY_PREFIX = "rag:intent:";

    private final KeywordIntentRouter delegate;
    private final StringRedisTemplate redis;
    private final AppProperties appProperties;

    /** 降级告警节流(Redis 故障时 60 秒一条) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    /**
     * 路由意图(带缓存)。
     *
     * @param message 用户消息
     * @return TOOL/KB/GENERAL
     */
    @Override
    public RagMode route(String message) {
        if (!appProperties.getIntentCache().isEnabled()) {
            return delegate.route(message);
        }
        try {
            long version = currentVersion();
            if (version < 0) {
                return delegate.route(message);
            }
            String key = key(message, version);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return RagMode.valueOf(cached);
            }
            RagMode mode = delegate.route(message);
            redis.opsForValue().set(key, mode.name(),
                    Duration.ofMinutes(appProperties.getIntentCache().getTtlMinutes()));
            return mode;
        } catch (Exception e) {
            // Redis 异常按未命中处理, 走真实路由
            degraded.warn("意图路由缓存异常(已降级: 走真实路由): {}", e.getMessage());
            return delegate.route(message);
        }
    }

    /**
     * 使全部意图路由缓存失效(版本号自增, 旧键不再被命中, TTL 到期自然清除)。
     *
     * @return 失效后的版本号; Redis 异常返回 -1
     */
    @Override
    public long evictAll() {
        try {
            Long version = redis.opsForValue().increment(VERSION_KEY);
            log.info("意图路由缓存已失效: version={}", version);
            return version == null ? -1L : version;
        } catch (Exception e) {
            degraded.warn("意图路由缓存版本自增失败(已降级: 旧结果仍在 TTL 内): {}", e.getMessage());
            return -1L;
        }
    }

    /** 读取当前路由版本号, 不存在时返回 0 */
    private long currentVersion() {
        String v = redis.opsForValue().get(VERSION_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    /** 缓存键: v{版本}:{归一化问题的 SHA-256} */
    private String key(String message, long version) {
        return KEY_PREFIX + "v" + version + ":" + digest(normalize(message));
    }

    /** 归一化: 去首尾空白 + 小写 + 移除全部空白字符(与语义缓存同口径) */
    private static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(question.trim().toLowerCase());
        int i = 0;
        while (i < sb.length()) {
            if (Character.isWhitespace(sb.charAt(i))) {
                sb.deleteCharAt(i);
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    /** SHA-256 摘要(hex) */
    private static String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
