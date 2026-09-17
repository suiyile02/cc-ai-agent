package com.ai.rag.service;

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
 * 意图路由结果缓存装饰器：把"问题 → 路由结果(TOOL/KB/GENERAL)"缓存在 Redis,
 * 相同/近似(归一化后一致)问题直接命中, 免去每轮关键词匹配与后续语义层的计算。
 *
 * <p>装饰器模式：包在真实路由({@link KeywordIntentRouter}, 后续可换 {@code CompositeIntentRouter})
 * 外层, 路由消费者只需注入 {@link IntentRouter} 即拿到带缓存版本。声明 {@code @Primary} 使
 * {@code IntentRouter} 注入歧义消解为该实现。
 *
 * <p>一致性/时效性设计:
 * <ul>
 *   <li>键 = {@code rag:intent:v{version}:{sha256(归一化问题)}}, 版本号存 Redis
 *       ({@code rag:intent:version}); 调 {@link #evictAll()} 自增版本即整体失效(旧键 TTL 自然清理);</li>
 *   <li>TTL 默认 60 分钟——路由结果是"问题属于哪类"的宽松判断, 短时陈旧可容忍,
 *       改词表后可手动清空立即生效, 不必等 TTL;</li>
 *   <li>Redis 异常一律按未命中处理并走真实路由, 绝不影响对话主流程。</li>
 * </ul>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class CachingIntentRouter implements IntentRouter, IntentCacheAdmin {

    /** 路由版本号键(调 evictAll 自增) */
    static final String VERSION_KEY = "rag:intent:version";
    private static final String KEY_PREFIX = "rag:intent:";

    private final KeywordIntentRouter delegate;
    private final StringRedisTemplate redis;
    private final AppProperties appProperties;

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
            log.debug("意图路由缓存异常(按未缓存处理): {}", e.getMessage());
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
            log.warn("意图路由缓存版本自增失败(Redis 不可用?): {}", e.getMessage());
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
