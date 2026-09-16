package com.ai.rag.service;

import com.ai.config.AppProperties;
import com.ai.rag.SemanticCacheAdmin;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;

/**
 * 语义缓存(P3-3)：以"归一化问题"为键缓存知识库问答的回答, 命中则跳过检索与模型调用。
 *
 * <p>设计要点:
 * <ul>
 *   <li>仅缓存"独立完整"的问题——经过多轮改写(含指代)的问题依赖会话上下文, 不入缓存;</li>
 *   <li>键 = 知识库版本号 + 归一化问题的 SHA-256: 知识库文档上传/删除/重处理会使版本号自增,
 *       旧缓存随 TTL 自然淘汰, 实现知识库变更联动失效;</li>
 *   <li>精确匹配(归一化后完全一致), 不做向量相似度匹配——相似度匹配存在"相近问题错配答案"的正确性风险;</li>
 *   <li>负缓存(穿透防护): 检索已执行且零命中(非超时降级)的问题写入短 TTL 的"无答案"标记,
 *       短时间内的重复提问直接返回固定"未找到"文案, 不再反复打检索+模型调用;
 *       降级导致的零命中不写负缓存, 避免把瞬时故障误判为无答案;</li>
 *   <li>Redis 异常一律降级为未命中, 绝不影响对话主流程。</li>
 * </ul>
 *
 * <p>命中与未命中在审计上可区分: 命中时跳过检索与上下文装配, 因此会留下一条
 * {@code rag_decision_log(rag_mode=KB, retrieval_executed=false)} 但<b>没有</b>对应的
 * {@code context_log} 记录; 未命中(KB)则两者都有。
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
                return null;
            }
            CachedAnswer answer = objectMapper.readValue(json, CachedAnswer.class);
            log.info("语义缓存命中: question={}", question);
            return answer;
        } catch (Exception e) {
            log.warn("语义缓存读取失败(按未命中处理): {}", e.getMessage());
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
            log.warn("语义缓存写入失败(忽略): {}", e.getMessage());
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
            log.warn("语义缓存版本号自增失败(Redis 不可用?): {}", e.getMessage());
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
            log.debug("语义负缓存检查失败(按未命中处理): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写入负缓存: 检索已执行但零命中(非降级) → 该问题在 missTtlMinutes 内视为无答案。
     * 键同样带知识库版本号, 文档变更随版本自增自然失效。
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
            log.debug("语义负缓存已写入: question={}", question);
        } catch (Exception e) {
            log.warn("语义负缓存写入失败(忽略): {}", e.getMessage());
        }
    }

    private boolean enabled() {
        return appProperties.getSemanticCache().isEnabled();
    }

    /** 获取当前版本号, 不存在时返回 0 */
    private long currentVersion() {
        String v = redis.opsForValue().get(VERSION_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    /** 正缓存键: v1:question-sha256 */
    private String answerKey(String question, long version) {
        return ANSWER_KEY_PREFIX + "v" + version + ":" + digest(normalize(question));
    }

    /** 负缓存键: v1:question-sha256(与正缓存同命名空间规则, 版本号一致) */
    private String missKey(String question, long version) {
        return MISS_KEY_PREFIX + "v" + version + ":" + digest(normalize(question));
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
