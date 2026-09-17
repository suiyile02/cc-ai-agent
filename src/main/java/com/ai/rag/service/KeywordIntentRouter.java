package com.ai.rag.service;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;

import com.ai.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于关键词表匹配的 {@link IntentRouter} 默认实现(三态规则引擎)：
 * <ol>
 *   <li>命中工具词表({@code AppProperties.Rag#toolKeywords}) → {@link RagMode#TOOL}
 *       ——答案在业务库, 跳过检索交给模型调工具;</li>
 *   <li>命中内部业务关键词({@code AppProperties.Rag#internalKeywords}) → {@link RagMode#KB}
 *       ——需要知识库检索;</li>
 *   <li>否则 → {@link RagMode#GENERAL} ——通用常识/闲聊, 跳过检索。</li>
 * </ol>
 * 工具词表优先于知识库词表判定(订单/物流等词同时出现在两表时按工具处理)。
 */
@Component
public class KeywordIntentRouter implements IntentRouter {

    private final List<String> internalKeywords;
    private final List<String> toolKeywords;

    /**
     * 构造路由器(词表来自 app.rag.internal-keywords / app.rag.tool-keywords 配置)。
     *
     * @param appProperties 应用配置
     */
    public KeywordIntentRouter(AppProperties appProperties) {
        this.internalKeywords = appProperties.getRag().getInternalKeywords();
        this.toolKeywords = appProperties.getRag().getToolKeywords();
    }

    /**
     * 按消息内容路由意图(三态)。
     *
     * @param message 用户消息
     * @return TOOL=工具类问题(跳过检索, 调工具); KB=命中知识库关键词, 需要检索;
     *         GENERAL=通用常识/闲聊, 跳过检索
     */
    @Override
    public RagMode route(String message) {
        if (isToolQuestion(message)) {
            return RagMode.TOOL;
        }
        return looksInternal(message) ? RagMode.KB : RagMode.GENERAL;
    }

    /**
     * 工具类问题判定：消息命中任一工具关键词(订单/物流/快递等)。
     *
     * @param message 用户消息
     * @return true=工具类问题
     */
    public boolean isToolQuestion(String message) {
        if (message == null || message.isBlank() || toolKeywords == null) {
            return false;
        }
        for (String keyword : toolKeywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
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
