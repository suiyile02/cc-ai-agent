package com.ai.session.service;

import com.ai.common.WarnThrottle;
import com.ai.session.entity.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 会话元数据 Redis 缓存(P3)：每轮对话的 requireActive 会话查询由 Redis 承担,
 * 降低 MySQL 压力。所有操作降级安全——Redis 异常一律回退 DB 查询, 不影响主流程。
 *
 * <p>键: {@code session:meta:<sessionId>}, TTL 10 分钟;
 * 归档/删除/状态变更时由 ChatSessionService 主动失效。
 * 不存在会话写入 {@code session:absent:<sessionId>} 短 TTL 负缓存(穿透防护):
 * 会话 ID 由服务端 UUID 生成, 不存在的 ID 短期内不会变为存在, 直接拒绝不再打 MySQL。
 *
 * <p>降级可观测性：异常一律 WARN(不得用 DEBUG——生产日志级别是 info, DEBUG 等于静默),
 * 并经 {@link WarnThrottle} 折成 60 秒一条, 避免 Redis 故障时每个请求刷 5 条日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCacheService {

    private static final String KEY_PREFIX = "session:meta:";
    private static final String NOT_FOUND_PREFIX = "session:absent:";
    private static final Duration TTL = Duration.ofMinutes(10);
    /** 负缓存 TTL: 足够挡住穿透, 又避免长期占用键(60 秒标准负缓存窗口) */
    private static final Duration NOT_FOUND_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 本组件共用的降级告警节流(Redis 故障时 60 秒一条) */
    private final WarnThrottle degraded = WarnThrottle.of(log);

    /**
     * 读取缓存会话。
     *
     * @param sessionId 会话 ID
     * @return 缓存的会话; 未命中或 Redis 异常返回 null
     */
    public ChatSession get(String sessionId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ChatSession.class);
        } catch (Exception e) {
            degraded.warn("会话缓存读取失败(已降级: 回退 MySQL): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入会话缓存。
     *
     * @param session 会话
     */
    public void put(ChatSession session) {
        try {
            redis.opsForValue().set(KEY_PREFIX + session.getSessionId(),
                    objectMapper.writeValueAsString(session), TTL);
        } catch (Exception e) {
            degraded.warn("会话缓存写入失败(已降级: 下次仍打 MySQL): {}", e.getMessage());
        }
    }

    /**
     * 会话是否已被负缓存标记为"不存在"(穿透防护: 命中则不再打 MySQL)。
     *
     * @param sessionId 会话 ID
     * @return true=已确认不存在; Redis 异常按未命中处理(回退 DB)
     */
    public boolean isNotFound(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(NOT_FOUND_PREFIX + sessionId));
        } catch (Exception e) {
            degraded.warn("会话负缓存检查失败(已降级: 回退 MySQL): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 写入负缓存: 标记会话不存在(短 TTL), 防不存在的会话 ID 反复穿透打 MySQL。
     *
     * @param sessionId 会话 ID
     */
    public void putNotFound(String sessionId) {
        try {
            redis.opsForValue().set(NOT_FOUND_PREFIX + sessionId, "1", NOT_FOUND_TTL);
        } catch (Exception e) {
            degraded.warn("会话负缓存写入失败(已降级: 穿透防护暂缺): {}", e.getMessage());
        }
    }

    /**
     * 失效会话缓存(归档/删除/状态变更时调用), 同时清除正/负缓存。
     *
     * @param sessionId 会话 ID
     */
    public void evict(String sessionId) {
        try {
            redis.delete(List.of(KEY_PREFIX + sessionId, NOT_FOUND_PREFIX + sessionId));
        } catch (Exception e) {
            degraded.warn("会话缓存失效失败(已降级: 旧缓存最长多存活 {} 分钟): {}",
                    TTL.toMinutes(), e.getMessage());
        }
    }
}
