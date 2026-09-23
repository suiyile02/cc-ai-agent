package com.ai.rag;

/**
 * 出口判定：把一次检索的事实折算成 {@link ChatOutcome}。
 *
 * <p>无状态纯函数，因此放在 rag 模块根包、以静态方法暴露，供 chat 编排与测试直接调用，
 * 不引入跨模块的 service 依赖。三条来自实测的规则：
 * <ol>
 *   <li>工具轮优先短路成 {@link ChatOutcome#TOOL_DATA}——它不跑检索，否则会落入"无命中"分支被误拒；</li>
 *   <li>{@code executed=false}（AGENT/非 RAG 会话）或 {@code degraded=true}（检索超时降级）都
 *       <b>不等于</b>"知识库没有答案"，一律 {@link ChatOutcome#ANSWERED_OPEN}；</li>
 *   <li>只有 BM25 命中不算证据——关键词路在小语料上对任何问题都能凑满名额，
 *       证据只认语义路过阈值。</li>
 * </ol>
 */
public final class OutcomeResolver {

    private OutcomeResolver() {
    }

    /**
     * 判定本轮出口。
     *
     * @param outcome   本轮检索结果（可为 {@link RetrievalOutcome#none()}）
     * @param toolTurn  本轮是否为工具轮（意图路由命中工具词表）
     * @param kbOnly    严格知识库模式开关
     * @param threshold 语义相似度阈值（证据门槛）
     * @return 出口
     */
    public static ChatOutcome resolve(RetrievalOutcome outcome, boolean toolTurn,
                                      boolean kbOnly, double threshold) {
        if (toolTurn) {
            return ChatOutcome.TOOL_DATA;
        }
        // 无检索或降级检索，一律开放域应答
        if (outcome == null || !outcome.executed() || outcome.degraded()) {
            return ChatOutcome.ANSWERED_OPEN;
        }
        // 判断向量库是否检索到内容
        boolean grounded = !outcome.hits().isEmpty()
                && outcome.semanticCount() > 0
                && outcome.semanticMaxScore() >= threshold;
        if (grounded) {
            return ChatOutcome.ANSWERED_FROM_KB;
        }
        return kbOnly ? ChatOutcome.REFUSED_NO_EVIDENCE : ChatOutcome.ANSWERED_OPEN;
    }
}
