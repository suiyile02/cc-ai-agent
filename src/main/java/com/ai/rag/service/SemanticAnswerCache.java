package com.ai.rag.service;

import com.ai.common.WarnThrottle;
import com.ai.config.AppProperties;
import com.ai.config.ChatClientProvider;
import com.ai.observability.ChatMetrics;
import com.ai.rag.SemanticCacheAdmin;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * 语义缓存（P3-3）：以"归一化问题"为键缓存知识库问答的回答，命中即跳过检索与上下文装配。
 *
 * <p>键 = {@code rag:answer:v{知识库版本}:m{对话模型}:{问题SHA-256}}（负缓存 {@code rag:miss:} 同命名空间）。
 * 两个失效维度：文档变更→版本自增；切换对话模型→{@code m} 段换命名空间。只做归一化后的精确匹配，
 * 不做相似度匹配——相近问题错配答案是正确性风险。
 *
 * <p>条目跨用户共享，因此写入侧必须排除含个性化/实时数据的回答（改写问题由前置的 cacheEligible 拦、
 * 工具轮次由收尾的 toolCalls 计数拦）；Redis 异常一律按未命中降级。
 * 不变式与降级口径见 {@code docs/flow-map.md} §13/§18 与 AGENTS.md「语义缓存与审计不变式」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticAnswerCache implements SemanticCacheAdmin {

    /** 知识库版本号键(每次文档变更自增) */
    static final String VERSION_KEY = "rag:kb:version";
    private static final String ANSWER_KEY_PREFIX = "rag:answer:";
    private static final String MISS_KEY_PREFIX = "rag:miss:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final ChatClientProvider chatClientProvider;

    /** 降级告警节流: Redis 故障时每请求多处降级, 折成 60 秒一条(见 {@link WarnThrottle}) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    /** 缓存的回答(来源为去扩展名的文档名列表) */
    public record CachedAnswer(String content, List<String> sources) {
    }

    /**
     * 查询缓存。
     *
     * @param question 归一化前的原始问题
     * @return 命中的回答; 未命中/未启用/Redis 异常返回 null
     */
    public CachedAnswer get(String question) {
        if (!enabled()) {
            return null;
        }
        try {
            long version = currentVersion();
            if (version < 0) {
                return null;
            }
            String json = redis.opsForValue().get(answerKey(question, version));
            if (json == null) {
                Metrics
                        .counter(ChatMetrics.SEMCACHE_MISS).increment();
                return null;
            }
            CachedAnswer answer = objectMapper.readValue(json, CachedAnswer.class);
            Metrics
                    .counter(ChatMetrics.SEMCACHE_HIT).increment();
            log.info("语义缓存命中: question={}", question);
            return answer;
        } catch (Exception e) {
            degraded.warn("语义缓存读取失败(已降级: 按未命中处理): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入缓存(由 ChatService 在 KB 命中且问题未改写时调用)。
     *
     * @param question 原始问题
     * @param content  回答全文
     * @param sources  来源文档名列表(去扩展名)
     */
    public void put(String question, String content, List<String> sources) {
        if (!enabled()) {
            return;
        }
        try {
            long version = currentVersion();
            if (version < 0) {
                return;
            }
            String json = objectMapper.writeValueAsString(new CachedAnswer(content, sources));
            redis.opsForValue().set(answerKey(question, version), json,
                    Duration.ofHours(appProperties.getSemanticCache().getTtlHours()));
            log.debug("语义缓存已写入: question={}", question);
        } catch (Exception e) {
            degraded.warn("语义缓存写入失败(已降级: 本轮不缓存): {}", e.getMessage());
        }
    }

    /**
     * 知识库变更联动失效: 版本号自增, 旧版本缓存键不再被命中(TTL 到期自然清除)。
     *
     * @return 失效后的知识库版本号; 缓存未启用或 Redis 异常时返回 -1(降级不抛异常)
     */
    @Override
    public long evictAll() {
        if (!enabled()) {
            return -1L;
        }
        try {
            Long version = redis.opsForValue().increment(VERSION_KEY);
            log.info("语义缓存已随知识库变更失效: kbVersion={}", version);
            return version == null ? -1L : version;
        } catch (Exception e) {
            degraded.warn("语义缓存版本号自增失败(已降级: 旧缓存仍在 TTL 内): {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * 查询是否已缓存"该问题无答案"(负缓存命中)。
     *
     * @param question 原始问题
     * @return true=命中负缓存(检索零命中, 短期内视为无答案); Redis 异常按未命中处理
     */
    public boolean isMiss(String question) {
        if (!enabled()) {
            return false;
        }
        try {
            long version = currentVersion();
            if (version < 0) {
                return false;
            }
            return Boolean.TRUE.equals(redis.hasKey(missKey(question, version)));
        } catch (Exception e) {
            degraded.warn("语义负缓存检查失败(已降级: 按未命中处理): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写入负缓存: 检索已执行但零命中(非降级) → 该问题在 missTtlMinutes 内视为无答案。
     * 键与正缓存同一命名空间(知识库版本号 + 模型标识), 文档变更与切换模型均随之失效。
     *
     * @param question 原始问题
     */
    public void putMiss(String question) {
        if (!enabled()) {
            return;
        }
        try {
            long version = currentVersion();
            if (version < 0) {
                return;
            }
            redis.opsForValue().set(missKey(question, version), "1",
                    Duration.ofMinutes(appProperties.getSemanticCache().getMissTtlMinutes()));
            Metrics
                    .counter(ChatMetrics.SEMCACHE_NEGATIVE).increment();
            log.debug("语义负缓存已写入: question={}", question);
        } catch (Exception e) {
            degraded.warn("语义负缓存写入失败(已降级: 穿透防护暂缺): {}", e.getMessage());
        }
    }

    /** 语义缓存总开关（{@code app.semantic-cache.enabled}）；关闭时全部方法零副作用。 */
    private boolean enabled() {
        return appProperties.getSemanticCache().isEnabled();
    }

    /** 获取当前版本号, 不存在时返回 0 */
    private long currentVersion() {
        String v = redis.opsForValue().get(VERSION_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    /**
     * 正缓存键: {@code rag:answer:v{知识库版本}:m{对话模型}:{问题sha256}}。
     * 模型维度使"切换对话模型"立即形成新的命名空间(旧模型的回答不再被命中, 随 TTL 淘汰)。
     */
    private String answerKey(String question, long version) {
        return ANSWER_KEY_PREFIX + namespace(version) + ":" + digest(normalize(question));
    }

    /**
     * 负缓存键: 与正缓存同一命名空间规则(版本号 + 模型标识一致), 仅前缀不同。
     */
    private String missKey(String question, long version) {
        return MISS_KEY_PREFIX + namespace(version) + ":" + digest(normalize(question));
    }

    /**
     * 键命名空间: {@code v{知识库版本}:m{对话模型}}——两个失效维度(知识库变更、模型变更)合一。
     *
     * @param version 当前知识库版本号
     * @return 命名空间片段(不含前缀)
     */
    private String namespace(long version) {
        return "v" + version + ":m" + modelTag();
    }

    /**
     * 模型标识(键的一部分)：只保留 Redis 键友好字符, 其余替换为下划线, 便于运维 grep。
     *
     * @return 模型标识, 如 {@code qwen3.8-max}
     */
    private String modelTag() {
        return chatClientProvider.modelLabel().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 归一化: 去首尾空白 + 小写 + 移除全部空白字符 */
    static String normalize(String question) {
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
    static String digest(String text) {
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
