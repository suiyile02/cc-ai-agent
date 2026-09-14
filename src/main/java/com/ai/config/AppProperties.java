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

    @Data
    public static class Rag {
        /** 向量集合名 */
        private String collectionName = "rag_knowledge_base";
        /** 检索 Top-K */
        private int topK = 5;
        /** 相似度阈值(实测该 embedding 模型分数量级偏低, 默认从 0.6 下调至 0.45, 避免误杀正确 chunk) */
        private double similarityThreshold = 0.45;
        /** 注入上下文字符上限 */
        private int contextMaxChars = 8000;
        /**
         * 意图路由开关(默认 true)：按问题内容决定是否需要 RAG 检索——
         * 命中内部业务关键词走检索；纯常识/闲聊问题跳过检索直接自由问答。
         * 设为 false 则恢复“RAG/HYBRID 会话每次都检索”的旧行为。
         */
        private boolean autoRoute = true;
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
        /** 主对话调用注入 enable_thinking=false(关闭思维链提速, 默认关闭以保证回答质量) */
        private boolean disableThinking = false;
    }

    @Data
    public static class Auth {
        /** JWT 签名密钥(生产通过环境变量 JWT_SECRET 注入, 建议长度≥32字节) */
        private String jwtSecret = "ai-agent-dev-secret-change-me-in-prod-2026";
        /** Token 有效期(小时) */
        private long tokenExpireHours = 168;
        /**
         * 开发便捷开关(默认关闭, 仅 dev profile 开启): true 时允许用 X-User-Id 请求头
         * 模拟登录(未携带 Token 时)。生产环境必须保持 false, 否则存在身份伪造风险。
         */
        private boolean devUserHeaderEnabled = false;
    }

    /** 演示数据播种(app.demo.*) */
    @Data
    public static class Demo {
        /** 是否在启动时播种演示数据(管理员/员工/订单, 仅空表时生效); 生产 profile 关闭 */
        private boolean seedEnabled = true;
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
}
