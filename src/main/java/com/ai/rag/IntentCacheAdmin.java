package com.ai.rag;

/**
 * 跨模块契约：意图路由结果缓存的管理动作(供 system 模块的运维接口调用)。
 *
 * <p>与 {@link SemanticCacheAdmin} 同一约定——rag 模块对外只暴露契约, 实现留在 service 包内。
 */
public interface IntentCacheAdmin {

    /**
     * 使全部意图路由缓存立即失效(版本号自增, 旧键不再命中, 无需等待 TTL)。
     *
     * <p>使用场景: 修改路由词表(关键字/同义词)后手动调用, 让新词表立即生效;
     * 正常情况下 TTL(默认 60 分钟)会自动淘汰旧结果。
     *
     * @return 失效后的路由版本号; 缓存未启用或 Redis 异常时返回 -1(降级不抛异常)
     */
    long evictAll();
}
