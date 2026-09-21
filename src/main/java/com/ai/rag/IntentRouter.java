package com.ai.rag;

/**
 * 意图路由契约：判定一次提问属于知识库类(KB)、工具类(TOOL)还是通用常识/闲聊(GENERAL)。
 *
 * <p><b>P3-7 起本判定的职责大幅收窄</b>：它<b>只</b>用于工具类的成本短路(跳过一次必然无用的知识库检索)，
 * <b>不再决定"这个问题要不要查知识库"</b>——那一判断已交给检索结果本身算出的
 * {@link ChatOutcome}。原因：让词表决定检索，等于让"有没有人记得改一份与文档无关的配置"
 * 决定"知识库里的内容能不能被问到"，且漏判是静默的。
 *
 * <p>当前实现为 {@link KeywordIntentRouter}(关键词匹配)。历史上的 Redis 缓存装饰器
 * {@code CachingIntentRouter} 已随 P3-7 B 批下线——它缓存的预判只剩"是否工具轮"，而底层的词表
 * {@code contains} 扫描是微秒级的纯内存操作，缓存反而多付两次网络往返。
 * 若将来要升级为语义/LLM 意图分类，提供同接口的新实现即可，调用方无需改动。
 */
public interface IntentRouter {

    /**
     * 按消息内容路由意图。
     *
     * @param message 用户消息
     * @return TOOL=工具类问题(跳过知识库检索, 交给模型调工具); KB/GENERAL 仅作审计标签,
     *         两者一律照常检索(是否据知识库作答由检索结果决定)
     */
    RagMode route(String message);
}
