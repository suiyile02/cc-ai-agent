package com.ai.rag.service;
import com.ai.rag.IntentRouter;
import com.ai.rag.RagMode;

import com.ai.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于关键词表匹配的 {@link IntentRouter} 唯一实现(三态规则引擎)：
 * <ol>
 *   <li>命中工具词表({@code AppProperties.Rag#toolKeywords}) → {@link RagMode#TOOL}
 *       ——答案在业务库, 跳过知识库检索交给模型调工具(这是本实现<b>唯一</b>影响链路的地方);</li>
 *   <li>命中内部业务关键词({@code AppProperties.Rag#internalKeywords}) → {@link RagMode#KB};</li>
 *   <li>否则 → {@link RagMode#GENERAL}。</li>
 * </ol>
 * 工具词表优先于知识库词表判定(订单/物流等词同时出现在两表时按工具处理)。
 *
 * <p><b>P3-7 起 KB 与 GENERAL 都照常检索</b>，后两态只剩审计标签的作用——"这个问题依据什么作答"
 * 由检索结果算出的 {@link com.ai.rag.ChatOutcome} 决定，不再由词表预判。
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
     * @return TOOL=工具类问题(跳过知识库检索, 调工具); KB/GENERAL 仅影响审计标签, 两者一律照常检索
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
