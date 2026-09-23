package com.ai.config.props;

import lombok.Data;

import java.util.List;

/**
 * 检索与意图路由配置({@code app.rag.*}), 从 {@code AppProperties.Rag} 迁出。
 *
 * <p>本类不加 @ConfigurationProperties: 绑定由聚合根 {@code AppProperties}(前缀 {@code app})统一负责。
 *
 * <p><b>默认值即生效值</b>: 本项目采用"Java 单一事实来源"策略——业务调参不在 application.yaml 里
 * 重复一遍, 因此这里改一行就等于改默认行为; 需要按环境区分时在 yaml 里写同名键覆盖即可。
 */
@Data
public class RagProps {

    /**
     * 向量集合名(单一事实来源): spring.ai.vectorstore.qdrant.collection-name 引用此值,
     * 修改集合名只需改这里(yaml)。
     */
    private String collectionName = "rag_knowledge_base";
    /** 检索 Top-K */
    private int topK = 5;
    /** 相似度阈值(实测该 embedding 模型分数量级偏低, 默认从 0.6 下调至 0.45, 避免误杀正确 chunk) */
    private double similarityThreshold = 0.45;
    /** 注入上下文字符上限 */
    private int contextMaxChars = 8000;
    /**
     * 混合检索开关(默认 true)：语义向量检索 + 关键词(BM25) 多路召回后再重排。
     * false 时退化为“仅语义向量检索 + 相似度阈值过滤”。
     */
    private boolean hybridEnabled = true;
    /**
     * 重排模式：score=分数融合重排(默认, 语义相似度+BM25 归一化加权)；
     * api=调用专用重排模型(DashScope 文本排序接口, 失败自动回退 score)；
     * llm=让对话大模型排序(需要模型可用, 失败自动回退 score)；
     * none=只做 RRF 排序、不做分数融合。
     */
    private String rerankMode = "score";
    /**
     * 重排服务地址({@code rerank-mode=api} 时使用), <b>填到 host(可带前缀)、不含排序路径</b>。
     *
     * <p>默认写阿里云百炼<b>公共</b>端点; 个人/企业专属 MaaS 实例的域名不通用, 需在
     * application.yaml 或环境变量里覆盖。排序路径 {@value #RERANK_PATH} 由代码补齐
     * (已含该路径时不重复拼接, 所以整条 URL 直接填这里也能用)。
     */
    private String rerankBaseUrl = "https://dashscope.aliyuncs.com";
    /** 重排服务完整路径(与 base-url 拼接; DashScope 文本排序接口的固定路径)。 */
    public static final String RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";
    /** 重排模型名(qwen3.7-text-rerank / gte-rerank 等同接口不同模型均可) */
    private String rerankModel = "qwen3.7-text-rerank";
    /**
     * 重排接口密钥。留空时复用 {@code spring.ai.openai.api-key}(同一 DashScope 账号通常同键),
     * 独立计费/独立权限的实例才需要单独注入。仓库内不放任何默认值。
     */
    private String rerankApiKey = "";
    /**
     * 重排调用超时毫秒(默认 3s)。
     *
     * <p>刻意给得短: 重排是"锦上添花"的一环, 超时即回退 score 融合, 不该把整轮对话拖慢。
     * 它同时受 {@link #retrieveTimeoutMs} 的整段预算约束。
     */
    private long rerankTimeoutMs = 3000;
    /**
     * 送重排模型的候选条数上限(默认 10)。
     *
     * <p>按条计费且延迟随条数增长; 排在后面的候选本来也进不了 Top-K, 多送只是花钱。
     */
    private int rerankCandidates = 10;
    /**
     * 单次检索阶段超时毫秒(整段预算: 嵌入 + 向量库 + 关键词 + 重排, 默认 10s)。
     *
     * <p>背景: 检索链路的嵌入/向量库调用原先没有任何超时保护, 实测出现过单轮检索卡
     * 100s 的情况(慢端点挂起 + 内置重试放大)。超时按"检索已执行但无命中"降级,
     * 对话继续作答不被阻塞——该降级在审计上可区分: {@code executed=true, final_hits=0},
     * 不会与"语义缓存命中"({@code executed=false})混淆。
     *
     * <p>正常耗时参考: 冷启动约 1.7s, 热态 0.2~0.6s, 故 10s 对正常链路足够宽裕。
     */
    private long retrieveTimeoutMs = 10_000;
    /**
     * 启动时自动重建关键词索引(默认开启): 从 Qdrant payload 滚动读回分块原文注册进
     * KeywordIndex(不重新向量化), 解决重启后 BM25 检索路失效。
     */
    private boolean autoRebuildIndex = true;
    /**
     * 意图路由关键词表({@code app.rag.internal-keywords})：auto-route=true 时,
     * 消息命中任一关键词判为知识库类问题(KB), 否则判为通用常识(GENERAL)跳过检索。
     * 缺省为内置企业内部高频词表, 可在 yaml 覆盖。
     */
    private List<String> internalKeywords = List.of(
            "年假", "病假", "事假", "休假", "假期", "请假", "考勤", "打卡", "加班", "调休", "迟到",
            "报销", "发票", "差旅", "出差", "住宿", "高铁", "发薪", "工资", "薪酬", "公积金", "社保", "补贴",
            "培训", "转岗", "绩效", "入职", "离职", "合同", "员工手册", "手册", "制度", "规定", "申请", "审批",
            "会议室", "预订", "信息安全", "订单", "单号", "物流", "快递", "员工", "部门", "职位", "电话", "邮箱",
            "福利", "标准", "公司",
            "放假", "节假日", "中秋", "国庆", "春节", "元旦", "调休", "补班");
    /**
     * 工具类问题关键词表({@code app.rag.tool-keywords})：命中即判为 TOOL 类意图——
     * 答案在业务库(经 BusinessTools 查询, 如 orders 表), 知识库检索查不到,
     * 因此跳过检索直接交给模型自主调工具。优先于 {@link #internalKeywords} 判定。
     *
     * <p>已内置少量高频同义说法(包裹/货运/物流信息), 缓解"换个说法就漏"；
     * 但关键词表本质上仍需人工维护(治标), 真正的语义泛化靠后续"意图检索层"(见 roadmap)。
     * 缺省词表见下, 可在 yaml 覆盖。
     */
    private List<String> toolKeywords = List.of(
            "订单", "单号", "物流", "快递", "运单", "发货", "收货", "跟踪",
            "包裹", "货运", "物流信息");
}
