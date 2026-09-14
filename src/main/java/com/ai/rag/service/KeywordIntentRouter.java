package com.ai.rag.service;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;

import com.ai.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于关键词表匹配的 {@link IntentRouter} 默认实现：
 * 消息命中任一内部业务关键词(缺省词表见 {@code AppProperties.Rag#internalKeywords},
 * 可由 app.rag.internal-keywords 覆盖)判为知识库类问题(KB), 否则判为通用常识/闲聊(GENERAL)。
 */
@Component
public class KeywordIntentRouter implements IntentRouter {

    private final List<String> internalKeywords;

    /**
     * 构造路由器(关键词表来自 app.rag.internal-keywords 配置)。
     *
     * @param appProperties 应用配置
     */
    public KeywordIntentRouter(AppProperties appProperties) {
        this.internalKeywords = appProperties.getRag().getInternalKeywords();
    }

    /**
     * 按消息内容路由意图。
     *
     * @param message 用户消息
     * @return KB=命中内部业务关键词, 需要检索; GENERAL=通用常识/闲聊, 跳过检索
     */
    @Override
    public RagMode route(String message) {
        return looksInternal(message) ? RagMode.KB : RagMode.GENERAL;
    }

    /**
     * 粗粒度意图判断：消息包含任一内部业务关键词即认为需要知识库/内部数据。
     *
     * @param message 用户消息
     * @return true=知识库类问题
     */
    private boolean looksInternal(String message) {
        if (message == null || message.isBlank() || internalKeywords == null) {
            return false;
        }
        for (String keyword : internalKeywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
