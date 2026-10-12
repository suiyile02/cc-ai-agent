package com.ai.config.props;

import java.util.List;
import lombok.Data;

/**
 * 上下文处理管线({@code app.context.*}): 统一 Token 预算 / 多轮查询改写 / 历史摘要压缩。
 * 从 {@code AppProperties.Context} 迁出, 四段子配置见 {@link Budget}、{@link QueryRewrite}、
 * {@link ShortQuery}、{@link Summary}、{@link History}。
 */
@Data
public class ContextProps {

    /** 模型上下文窗口上限(token) */
    private int modelMaxTokens = 8192;
    /** 为模型回答预留的 token(可用上下文预算 = modelMaxTokens - reservedForAnswer) */
    private int reservedForAnswer = 1500;
    /** 各段预算占比 */
    private Budget budget = new Budget();
    /** 多轮查询改写配置 */
    private QueryRewrite queryRewrite = new QueryRewrite();
    /** 短查询扩展配置(补全成可检索的完整问题) */
    private ShortQuery shortQuery = new ShortQuery();
    /** 历史滚动摘要配置 */
    private Summary summary = new Summary();
    /** 历史窗口配置 */
    private History history = new History();

    /**
     * RAG 段 Token 预算(装配层与检索展开层共用的单一来源)。
     *
     * <p>总预算 = max(0, modelMaxTokens - reservedForAnswer), RAG 段按占比 {@code budget.rag}
     * 折算。检索层的预算感知父块展开与装配层的注入共用此值, 避免两处公式漂移。
     *
     * @return RAG 段可用 token 数
     */
    public int ragTokenBudget() {
        int total = Math.max(0, modelMaxTokens - reservedForAnswer);
        return (int) (total * budget.getRag());
    }

    /** 上下文各段 token 预算占比(总预算 = modelMaxTokens - reservedForAnswer) */
    @Data
    public static class Budget {
        /**
         * system 提示词(不含 RAG 段)占比。
         *
         * <p>【未被读取】ContextAssembler 只读取 rag/history/user 三个占比,
         * system 段预算由 total 减去实算的 rag/history/user 得到; 此配置项当前不生效,
         * 保留仅为语义完整。
         */
        private double system = 0.15;
        /** 历史(摘要 + 最近窗口)占比 */
        private double history = 0.35;
        /** RAG 检索上下文占比 */
        private double rag = 0.40;
        /** 当前用户输入占比 */
        private double user = 0.10;
    }

    /** 多轮查询改写(指代消解/上下文补全) */
    @Data
    public static class QueryRewrite {
        /** 改写开关 */
        private boolean enabled = true;
        /** 触发改写所需的最小历史轮数(首轮跳过) */
        private int minHistoryTurns = 1;
        /**
         * 改写调用超时(ms), 超时回退原始问题; 连续失败≥2 次熔断 60s。
         *
         * <p>默认 30s: 预算小于端点真实耗时时改写永远超时(白等一次又退回原问题)。
         */
        private long timeoutMs = 30_000;
        /**
         * 指代线索启发式(默认开启): 仅当问题包含 {@link #referenceHints} 中的
         * 指代/省略线索词才触发改写——完整问题(如"张三在哪个部门？")零等待直接检索,
         * 避免每轮多等一次 LLM 改写。
         */
        private boolean referenceHintRequired = true;
        /** 指代/省略线索词表(问题含任一词即触发改写) */
        private List<String> referenceHints = List.of(
                "那", "它", "他", "她", "这个", "这些", "那些", "上面", "刚才",
                "继续", "另外", "其中", "再", "也", "前面", "刚才说的", "该");
        /** 改写调用注入 enable_thinking=false(qwen3 类模型关闭思维链, 实测可大幅降低改写延迟) */
        private boolean disableThinking = true;
    }

    /**
     * 短查询扩展({@code app.context.short-query.*})：把"产品""报销"这类过短提问补全成可检索的完整问题。
     *
     * <p>存在的理由：embedding 对裸词给出的余弦分显著偏低, 而出口判据要求向量路过阈值——短词
     * 于是被误判为"知识库无依据"。扩展只改检索用词, 不改用户原问题(详见 {@code ShortQueryExpander})。
     *
     * <p><b>扩展成功的轮次等同"已改写"</b>：语义缓存的准入据此排除——缓存条目跨用户共享,
     * 用扩写词检索出来的答案挂到原始短词键上会让后来者拿到跑题的回答。
     */
    @Data
    public static class ShortQuery {
        /** 扩展开关 */
        private boolean enabled = true;
        /**
         * 触发阈值：问题去除空白后的字符数小于该值才扩展（默认 7）。
         *
         * <p><b>2026-10-13 由 6 调为 7</b>：实测 6 字查询基本是名词短语（"信息安全要求"），
         * 向量检索对短语的匹配显著弱于完整问句——该词原样检索最高分 0.4151（不过阈值被拒答），
         * 扩展成"信息安全要求的保密性、完整性和可用性分别有哪些具体规定？"后升到 0.4771（过阈值、命中正确文档）。
         * 6 字样本（信息安全要求/告警处理流程/员工手册内容/差旅报销标准）扩展后 3 升 1 平；
         * 8 字以上完整问句扩展后普遍略降（0.65→0.62 等）但均仍过阈值——故边界定在 7，不再放宽。
         * 样本有限（6 字仅 4 例），语料扩充后须复跑（评测脚本 {@code docs/eval/rag_eval.py}）。
         */
        private int minChars = 7;
        /** 扩展调用超时(ms), 超时/失败/结果空一律回退原问题 */
        private long timeoutMs = 3000;
        /** 扩展结果长度上限(模型啰嗦时截断, 避免把一整段解释喂给检索) */
        private int maxChars = 60;
        /** 扩展调用注入 enable_thinking=false(与改写同口径) */
        private boolean disableThinking = true;
    }

    /** 历史滚动摘要压缩 */
    @Data
    public static class Summary {
        /** 摘要开关 */
        private boolean enabled = true;
        /** 历史消息数超过该阈值时触发摘要压缩 */
        private int triggerMessages = 20;
        /** 压缩后保留的最近消息条数(不并入摘要) */
        private int keepRecentMessages = 8;
        /** 摘要文本 token 上限 */
        private int maxTokens = 400;
        /** 摘要模型调用超时(ms), 超时/失败保留原摘要不裁剪(后台异步执行, 不阻塞对话) */
        private long timeoutMs = 30000;
        /** 历史消息 Token 阈值(异步摘要的第二触发条件); 0=关闭, 仅按条数触发 */
        private long triggerTokens = 0;
        /** 摘要调用注入 enable_thinking=false(qwen3 类模型关闭思维链, 加快后台摘要就绪) */
        private boolean disableThinking = true;
    }

    /** 历史窗口 */
    @Data
    public static class History {
        /** 窗口硬上限(条), 与 token 预算取更严者 */
        private int maxMessages = 30;
    }
}
