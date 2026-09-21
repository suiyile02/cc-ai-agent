package com.ai.rag;

/**
 * 意图路由契约：判定一次提问属于知识库类(KB)、工具类(TOOL)还是通用常识/闲聊(GENERAL)。
 *
 * <p><b>P3-7 起本判定的职责大幅收窄</b>：它<b>只</b>用于工具类的成本短路(跳过一次必然无用的知识库检索)，
 * <b>不再决定"这个问题要不要查知识库"</b>——那一判断已交给检索结果本身算出的
 * {@link ChatOutcome}。原因：让词表决定检索，等于让"有没有人记得改一份与文档无关的配置"
 * 决定"知识库里的内容能不能被问到"，且漏判是静默的。
 *
 * <p>当前实现为 {@link KeywordIntentRouter}(关键词匹配)；{@link CachingIntentRouter} 是其 Redis 缓存装饰器。
 * 若将来要升级为语义/LLM 意图分类，提供同接口的新实现即可，调用方无需改动。
 */
public interface IntentRouter {

    /**
     * 按消息内容路由意图。
     *
     * @param message 用户消息
     * @return KB=命中内部业务关键词, 需要检索; GENERAL=通用常识/闲聊, 跳过检索
     */
    RagMode route(String message);
}
