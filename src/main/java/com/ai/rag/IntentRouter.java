package com.ai.rag;

/**
 * 意图路由契约：判定一次提问属于知识库类(KB)还是通用常识/闲聊(GENERAL)，
 * 供对话(chat)模块在检索前调用。
 *
 * <p>当前实现为 {@link KeywordIntentRouter}(关键词匹配)；如需升级为 LLM 意图分类,
 * 提供同接口的新实现即可, 调用方无需改动。{@code app.rag.auto-route=false} 时
 * 调用方应跳过路由、一律按 KB 处理。
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
