package com.ai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 可用性启动自检(降级可观测)：应用就绪后探一次 Redis, 把"哪些能力此刻处于降级态"一次说清。
 *
 * <p>动机：项目里 Redis 承担 5 项能力(登录限流/会话缓存/语义缓存/意图缓存/令牌黑名单),
 * 且全部是"异常即降级"——Redis 没起或中途挂掉时**功能依然正常**, 只是失去缓存与防线。
 * 没有这行自检日志, 运维只能从"缓存突然全不命中"反推, 极难定位(历史上就出现过降级日志是 DEBUG
 * 级别、生产 info 级别下完全静默的情况)。
 *
 * <p>约束：探针**绝不影响启动**——异常全部内部吸收, 只输出日志, 不抛不重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisReadinessProbe implements ApplicationRunner {

    /** 探测键(只读; 用业务前缀便于与真实键一起 grep 到) */
    private static final String PROBE_KEY = "login:lock:u:__probe__";

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;
    private final Environment environment;

    /**
     * 启动后立即探测并输出能力状态汇总。
     *
     * @param args 启动参数(未使用)
     */
    @Override
    public void run(ApplicationArguments args) {
        String failure = probeFailure();
        if (failure != null) {
            log.warn("Redis 自检失败(不可用): {} —— 以下能力已降级: {}; 应用仍可正常提供登录与对话",
                    failure, String.join("; ", degradedCapabilities()));
            return;
        }
        log.info("Redis 自检通过: 登录限流后端={}, 语义缓存={}, 意图缓存={}, 会话缓存=启用, "
                        + "令牌黑名单降级口径={}",
                environment.getProperty("app.auth.rate-limit-backend", "redis"),
                appProperties.getSemanticCache().isEnabled() ? "启用" : "关闭",
                appProperties.getIntentCache().isEnabled() ? "启用" : "关闭",
                appProperties.getAuth().isBlacklistFailOpen() ? "放行(仅开发可接受)" : "拒绝(生产基线)");
    }

    /**
     * 探测 Redis：任何异常都视为不可用并返回原因, 绝不外抛。
     *
     * @return null=可用; 否则为异常摘要(类型 + 消息)
     */
    private String probeFailure() {
        try {
            redis.hasKey(PROBE_KEY);
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * 列出 Redis 不可用时被降级的能力(与代码实际降级口径一致, 供启动日志核对)。
     *
     * @return 降级说明列表
     */
    private List<String> degradedCapabilities() {
        List<String> list = new ArrayList<>();
        list.add("登录限流→按未锁定放行(爆破防护暂缺)");
        if (appProperties.getSemanticCache().isEnabled()) {
            list.add("语义缓存→按未命中处理(每轮都走检索+模型)");
        }
        if (appProperties.getIntentCache().isEnabled()) {
            list.add("意图路由缓存→按未命中走真实路由");
        }
        list.add("会话缓存→回退 MySQL 查询");
        list.add("令牌黑名单→" + (appProperties.getAuth().isBlacklistFailOpen()
                ? "放行(已注销的令牌在过期前仍可用!)" : "拒绝(需重新登录)"));
        return list;
    }
}
