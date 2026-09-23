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
         * 改写调用超时(ms), 超时回退原始问题。
         *
         * <p>默认给到 30s 而非"看起来更合理"的几秒: 实测该端点单次改写要 10.7~28.9s,
         * 预算小于真实耗时时改写永远在超时——等于白等一次又退回原问题。连续失败≥2 次熔断 60s。
         */
        private long timeoutMs = 30_000;
        /**
         * 指代线索启发式(默认开启): 仅当问题包含 {@link #referenceHints} 中的
         * 指代/省略线索词才触发改写——完整问题(如"张三在哪个部门？")零等待直接检索,
         * 避免每轮多等一次 LLM 改写(实测 10~29s)。
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
     * <p>存在的理由：embedding 对 2~4 字的裸词给出的余弦分显著偏低(实测"产品"0.410 vs
     * 完整句"你们产品的定价和套餐分别是什么"0.720, 同一文档同一分块), 而出口判据要求向量路过阈值,
     * 于是"库里明明有却答无据"。扩展只改检索用词, 不改用户原问题(对话与日志仍用原文)。
     *
     * <p><b>扩展成功的轮次等同"已改写"</b>：语义缓存的准入据此排除——缓存条目跨用户共享,
     * 用扩写词检索出来的答案挂到原始短词键上会让后来者拿到跑题的回答。
     */
    @Data
    public static class ShortQuery {
        /** 扩展开关 */
        private boolean enabled = true;
        /** 触发阈值：问题去除空白后的字符数小于该值才扩展(默认 6, 即"产品""加班调休"这类) */
        private int minChars = 6;
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
