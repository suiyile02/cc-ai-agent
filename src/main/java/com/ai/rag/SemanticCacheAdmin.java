package com.ai.rag;

/**
 * 跨模块契约：语义缓存的管理动作(供 system 模块的运维接口调用)。
 *
 * <p>调用方依赖本接口而非 {@code SemanticAnswerCache} 实现, 与 {@link RagRetriever}、
 * {@link IntentRouter} 同一约定——rag 模块对外只暴露契约, 实现留在 service 包内。
 */
public interface SemanticCacheAdmin {

    /**
     * 使全部语义缓存立即失效(知识库版本号自增, 旧键不再命中, 无需等待 TTL)。
     *
     * <p>使用场景: 知识库文档变更(上传/删除/重处理)已自动联动, 此方法供运维在
     * "回答质量异常/切换模型"等情形下手动清空。
     *
     * @return 失效后的知识库版本号; 缓存未启用或 Redis 异常时返回 -1(降级不抛异常)
     */
    long evictAll();
}
