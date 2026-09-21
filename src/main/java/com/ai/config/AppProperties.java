package com.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 业务自定义配置(app.*)。字段与 application.yaml 对应。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Rag rag = new Rag();
    private Storage storage = new Storage();
    private Chat chat = new Chat();
    private Auth auth = new Auth();
    private Context context = new Context();
    private Demo demo = new Demo();
    private Cors cors = new Cors();
    private LogRetention logRetention = new LogRetention();
    private SemanticCache semanticCache = new SemanticCache();
    private Ingestion ingestion = new Ingestion();
    private Concurrency concurrency = new Concurrency();
    private SessionTitle sessionTitle = new SessionTitle();

    @Data
    public static class Rag {
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
         * llm=调用大模型重排(需要模型可用, 失败自动回退 score)；
         * none=只做 RRF 排序、不做分数融合。
         */
        private String rerankMode = "score";
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

    @Data
    public static class Storage {
        /** 上传文件本地存储根目录 */
        private String path = "./data/files";
    }

    @Data
    public static class Chat {
        /** chat_log.model_name 兜底标签：优先从 ChatModel 默认选项解析实际模型名(见 ChatService) */
        private String modelLabel = "qwen3.7-flash";
        /**
         * 主对话注入 enable_thinking=false(默认 true 关闭思维链)。
         *
         * <p>关闭思维链的原因(2026-09 实测):
         * <ol>
         *   <li><b>稳定性</b>: qwen3 思考模式在长推理时可能整段静默不出字(>60s),
         *       撞上 OpenAI 客户端 okhttp 的 60s read timeout, 流被重置
         *       ({@code InterruptedIOException + StreamResetException: CANCEL}),
         *       整轮回答变成一条中断提示——线上"流式对话中断"的根因;</li>
         *   <li><b>延迟</b>: 关闭后首字秒级、总耗时从 10~60s 降到 3~8s
         *       (改写/摘要早已关闭思维链, 质量无碍)。</li>
         * </ol>
         * 若确需向用户展示思考过程, 可置 false 恢复, 但需把
         * {@code stream-idle-timeout-ms} 调大或关闭以容忍长思考静默。
         */
        private boolean disableThinking = true;
        /**
         * 流式请求是否索取 usage(默认开启)。
         *
         * <p>严格遵循 OpenAI 规范的端点只在该字段置位时, 才会在末尾发一个 usage-only 分块;
         * DashScope 兼容模式实测无论是否索取都会返回(仅作保险)。不索取时
         * {@code chat_log.total_tokens} 会恒为空——流式请求注入, 同步请求不注入
         * (部分端点会拒绝 stream_options 与 stream=false 并存)。网关不认该字段时置 false。
         */
        private boolean streamIncludeUsage = true;
        /**
         * 流式静默超时毫秒(默认 20s): 相邻两个增量间隔超过该值(或首增量迟迟不来)即主动终止本轮
         * 并降级为友好提示, 早于上游 okhttp 的 60s read timeout 触发。
         *
         * <p>背景: 模型长思考/网络挂起时会长时间无输出, 若不主动止损, 请求会卡到 okhttp 超时
         * 才被重置(实测整轮 61s 只返回一条 19 字提示)。0=关闭该保护(不推荐)。
         */
        private long streamIdleTimeoutMs = 20_000;
        /**
         * 严格知识库模式(默认关闭)。
         *
         * <p>开启后主对话只能依据「知识库检索到的资料」或「业务工具返回结果」作答, 禁止模型用
         * 自身预训练知识回答公司内部事务; 无资料/资料无关时按固定口径友好拒答。
         * 影响范围: 提示词选择({@code PromptService.systemFor})——GENERAL 与 TOOL 不再套用
         * "可回答常识科普闲聊"的自由作答模板; {@code AGENT} 会话不受影响(本就靠工具作答)。
         *
         * <p><b>这是提示词级软约束, 不是硬保证</b>: 模型仍被调用, 极端情况仍可能不遵守。
         * 需要"保证零编造"时应在检索零命中处直接返回固定文案(硬闸门), 而非依赖本开关。
         */
        private boolean kbOnly = true;
    }

    @Data
    public static class Auth {
        /**
         * JWT 签名密钥。仓库内不提供任何默认值：留空时非生产自动生成一次性随机密钥,
         * 生产由 SecurityConfigValidator 拒绝启动。生产经 JWT_SECRET 环境变量注入(≥32字节)。
         */
        private String jwtSecret = "";
        /** Token 有效期(小时) */
        private long tokenExpireHours = 168;
        /**
         * 开发便捷开关(默认关闭, 仅 dev profile 开启): true 时允许用 X-User-Id 请求头
         * 模拟登录(未携带 Token 时)。生产环境必须保持 false, 否则存在身份伪造风险。
         */
        private boolean devUserHeaderEnabled = false;
        /**
         * 令牌黑名单降级策略: true=Redis 异常时放行(保可用), false=拒绝(保安全, 生产建议)。
         */
        private boolean blacklistFailOpen = true;
    }

    /** 演示数据播种(app.demo.*) */
    @Data
    public static class Demo {
        /** 是否在启动时播种演示数据(管理员/员工/订单, 仅空表时生效); 生产 profile 关闭 */
        private boolean seedEnabled = true;
        /** 播种默认管理员时使用的初始口令(仅演示; 生产应关闭播种或用 DEMO_ADMIN_PASSWORD 注入) */
        private String adminPassword = "admin123";
    }

    /** 跨域配置(app.cors.*)：对接前端(Vue3)时放行的来源 */
    @Data
    public static class Cors {
        /** 允许跨域的来源列表; 为空则不注册 CORS 映射 */
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }

    /**
     * 上下文处理管线(app.context.*)：统一 Token 预算 / 多轮查询改写 / 历史摘要压缩。
     */
    @Data
    public static class Context {
        /** 模型上下文窗口上限(token) */
        private int modelMaxTokens = 8192;
        /** 为模型回答预留的 token(可用上下文预算 = modelMaxTokens - reservedForAnswer) */
        private int reservedForAnswer = 1500;
        /** 各段预算占比 */
        private Budget budget = new Budget();
        /** 多轮查询改写配置 */
        private QueryRewrite queryRewrite = new QueryRewrite();
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
             * system 段预算由 total 减去实算的 rag/history/user 得到; 此配置项当前不生效
             * (yaml 中 app.context.budget.system 保留仅为语义完整)。
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
            /** 改写调用超时(ms), 超时回退原始问题 */
            private long timeoutMs = 3000;
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

    /** 日志保留期清理(app.log-retention.*)：四类审计日志按保留天数定时清理(P2-4) */
    @Data
    public static class LogRetention {
        /** 是否启用定时清理 */
        private boolean enabled = true;
        /** 四类审计日志保留天数 */
        private int retentionDays = 180;
        /** 执行 cron(默认每天凌晨 3 点) */
        private String cron = "0 0 3 * * ?";
    }

    /** 语义缓存(app.semantic-cache.*)：相同知识库问题的回答缓存(P3-3, 命中跳过检索+模型调用) */
    @Data
    public static class SemanticCache {
        /** 是否启用 */
        private boolean enabled = true;
        /** 正缓存有效期(小时); 知识库文档上传/删除/重处理会使全部缓存立即失效 */
        private int ttlHours = 1;
        /** 负缓存有效期(分钟): 检索零命中的问题短时间视为无答案, 防穿透反复打检索+模型 */
        private int missTtlMinutes = 30;
    }

    /** 文档解析防护(app.ingestion.*)：解析超时与解压炸弹预检(A2) */
    @Data
    public static class Ingestion {
        /** 单文档解析超时毫秒(超时置 status=3) */
        private long parseTimeoutMs = 120000;
        /** docx 解压后内容总大小上限(字节, 默认 512MB, 超限疑似压缩炸弹) */
        private long maxUncompressedBytes = 536870912L;
        /** docx 内部条目数上限 */
        private int maxZipEntries = 5000;
    }

    /** 并发防护(app.concurrency.*)：单用户并发对话上限(B3) */
    @Data
    public static class Concurrency {
        /** 单用户最大并发对话数 */
        private int maxConcurrentPerUser = 3;
        /** 获取并发名额的等待时长毫秒(超时拒绝) */
        private long acquireTimeoutMs = 1000;
    }

    /**
     * 会话自动标题(app.session-title.*)：首轮把"未命名会话"填成有意义的标题。
     *
     * <p>两段式——先用问题前若干字同步占位(零等待, 保证任何情况下都有标题),
     * 再用模型概括精修(异步, 与本轮回答并行)。详见 {@code com.ai.session.service.SessionTitleService}。
     */
    @Data
    public static class SessionTitle {
        /** 总开关(关闭则标题保持为空, 前端显示"未命名会话") */
        private boolean enabled = true;
        /** 兜底标题截取长度(按 UTF-16 单元计, 不会切开 emoji 代理对) */
        private int fallbackChars = 24;
        /** 是否调用模型精修(关闭则一直停留在兜底的截断标题, 零模型开销) */
        private boolean modelEnabled = false;
        /** 精修标题长度上限(模型不守规矩时的硬截断保护) */
        private int maxChars = 30;
        /** 精修调用超时毫秒(超时放弃, 保留兜底标题) */
        private long timeoutMs = 8000;
    }
}
