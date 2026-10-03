package com.ai.rag;

/**
 * 出口判定：把一次检索的事实折算成 {@link ChatOutcome}。
 *
 * <p>无状态纯函数，因此放在 rag 模块根包、以静态方法暴露，供 chat 编排与测试直接调用，
 * 不引入跨模块的 service 依赖。四条来自实测的规则：
 * <ol>
 *   <li>未执行检索（AGENT/非 RAG 会话）或降级（检索超时）都<b>不等于</b>"知识库无答案"，
 *       一律 {@link ChatOutcome#ANSWERED_OPEN}；</li>
 *   <li>只有 BM25 命中不算证据——关键词路在小语料上对任何问题都能凑满名额，
 *       证据只认语义路过阈值；</li>
 *   <li>零命中且会话注册了业务工具 → {@link ChatOutcome#NO_EVIDENCE_WITH_TOOLS}：
 *       不直接拒答，交给模型按工具描述裁决（调工具作答或固定口径拒答）；</li>
 *   <li>零命中且无工具可调（纯 RAG 会话）→ 严格模式固定口径拒答。</li>
 * </ol>
 * 旧"工具轮优先短路成 TOOL_DATA、跳过检索"已废除——词表按实体名词预判会把知识库能答的
 * 问题错拒，现在 RAG/HYBRID 一律检索后按事实分叉（设计取舍见 docs/optimization-roadmap.md）。
 */
public final class OutcomeResolver {

    private OutcomeResolver() {
    }

    /**
     * 判定本轮出口。
     *
     * @param outcome        本轮检索结果（可为 {@link RetrievalOutcome#none()}）
     * @param toolsAvailable 本会话是否注册了业务工具(HYBRID=true, RAG=false; 会话级静态事实)
     * @param kbOnly         严格知识库模式开关
     * @param threshold      语义相似度阈值（证据门槛）
     * @return 出口
     */
    public static ChatOutcome resolve(RetrievalOutcome outcome, boolean toolsAvailable,
                                      boolean kbOnly, double threshold) {
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
        // 零命中: 有工具可调 → 交给模型裁决(调工具或拒答); 无工具 → 固定口径拒答
        if (kbOnly && toolsAvailable) {
            return ChatOutcome.NO_EVIDENCE_WITH_TOOLS;
        }
        return kbOnly ? ChatOutcome.REFUSED_NO_EVIDENCE : ChatOutcome.ANSWERED_OPEN;
    }
}
