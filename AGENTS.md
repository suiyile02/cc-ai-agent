# Agent Instructions for Spring AI RAG Project (MyBatis-Plus)

## 项目概览 (Project Overview)

- **架构模式:** 标准 Spring MVC (Model-View-Controller)。
    - **Controller:** 处理 HTTP 请求，参数校验，返回统一 DTO。
    - **Service:** 业务逻辑，事务管理，调用 AI 服务。
    - **Mapper:** 数据访问 (MyBatis-Plus BaseMapper + LambdaQueryWrapper, MySQL)。
- **核心栈:** Java 21 (Virtual Threads), Spring Boot 4, Spring AI 2, MyBatis-Plus 3.5。
- **数据存储:**
    - **向量库:** Qdrant (RAG 上下文, 唯一向量库)。
    - **关系库:** MySQL (业务数据, 唯一关系库)。

---

## 项目架构规范 (模块化包结构, 强制遵守)

### 包结构总览 (模块优先)

```
com.ai
├── common        全局公共层(业务无关): 统一响应 Result/PageResult · ErrorCode · BusinessException
│                 · GlobalExceptionHandler · 工具类(Strings/MessageTextRenderer/Timeouts/DateParamUtils)
│                 · Token 计量(TokenCounter 接口 / JtokTokenCounter 精确实现 / HeuristicTokenCounter 备用)
├── aspect        全局切面层: 跨模块 AOP 统一管理(SelfOrAdminAspect 授权 · ToolCallLogAspect 工具日志)
├── config        全局配置层: AppProperties(聚合根) + props/*Props(各段实体与默认值) · 异步池 · ChatClient 装配 · MVC/跨域 · MyBatis-Plus 装配 · 种子数据
├── prompt        提示词模板装配(PromptService, classpath:/prompts/*.st)
├── rag           RAG 能力模块: 模块根=对外契约(RagRetriever/IntentRouter/RagMode/RetrievalOutcome/
│                 ChatOutcome/OutcomeResolver 契约面/SourceVO)
│   └── service   实现: 编排 RagRetrievalService + 协作类(HybridRecaller/RrfFuser/RetrievalCandidate/
│                 RerankStrategy×3+工厂/RagContextRenderer/DocumentMeta) · KeywordIndex · 语义缓存
├── context       上下文管线模块: 模块根=契约(ConversationMemory/HistoryContext/AssembledPrompt/ContextComposition)
│   ├── entity    ConversationSummary      ├── mapper  ConversationSummaryMapper
│   └── service   ContextAssembler/ConversationMemoryService/ConversationSummarizer/QueryRewriter/
│                 ShortQueryExpander
├── chat          对话模块: controller/ChatController · service/ChatService · event/审计事件(值对象) · dto
├── knowledge     知识库模块: controller/service/entity/mapper/dto
├── user          用户鉴权模块: controller/service/entity/mapper/dto
│   └── security  JwtTokenProvider · UserContext · AuthInterceptor · PasswordHasher · RequireSelfOrAdmin
├── session       会话模块: SessionType(模块根枚举, 跨模块共享) · controller/service/entity/mapper/dto
├── system        系统审计模块: controller/SystemController · service/四类日志服务+ChatAuditListener
│   ├── entity    ChatLog/ToolCallLog/RagDecisionLog/ContextLog    ├── mapper  对应四个
│   └── dto       四个日志 VO
├── agent         Agent 工具模块: BusinessTools(@Tool) + entity/mapper/dto(员工/订单演示数据)
└── memory        对话记忆存储模块: DbChatMemoryRepository + entity/mapper(SPRING_AI_CHAT_MEMORY)
```

### 模块内子包约定

| 子包 | 放什么 |
|---|---|
| `controller` | REST 端点, 仅请求映射/参数绑定/调用 Service |
| `service` | 业务逻辑、事务、领域监听器(如 ChatAuditListener) |
| `entity` | 本模块拥有的 MyBatis-Plus 实体(与表一一对应) |
| `mapper` | 本模块的 BaseMapper 接口(新增后必须在 MybatisPlusConfig.@MapperScan 注册) |
| `dto` | 本模块的请求/响应 record(VO/Request) |
| 模块根包 | 对外契约: 接口、枚举、被其它模块消费的 record(如 RagRetriever) |
| 其它 | 允许按需新增语义化子包(如 user/security、chat/event), 须在本文档登记 |

### 强制规则

1. **新增功能先定位模块**: 按"知识库/对话/用户/会话/系统审计/RAG/上下文"归属; 新领域则新建 `com.ai.<module>` 并登记到本文档。
2. **实体/Mapper/DTO 跟模块走**: 表与实体属于唯一模块, 放 `<module>.entity`/`<module>.mapper`, 不再使用全局 `com.ai.entity`/`com.ai.mapper`。
3. **切面统一管理**: 所有 `@Aspect` 类放 `com.ai.aspect`, 模块内禁止私切身面; 切面只做横切判定(授权/审计), 数据逻辑留在 Service。
4. **common 业务无关**: 禁止依赖任何业务模块; entity 禁止依赖 service/controller/aspect; service 禁止依赖 controller; Controller 禁止直连 Mapper。
5. **跨模块调用**: 只能引用对方**模块根包**的契约类型(接口/枚举/值对象)。默认**禁止**引用其它模块的
   `entity`/`mapper`/`dto`: 需要跨模块传递的类型应由生产方放到自己的模块根包, 或由消费方定义值对象
   (例: `ChatDecisionEvent` 携带算好的值, 不直接传 `system.entity.RagDecisionLog`)。
   已知妥协: `session.entity.ChatSession` 被 chat/context/prompt 当作会话身份载体直接引用(共 9 处),
   新增这类引用必须先评估能否改为在 `session` 根包提供契约视图, 不得默默扩散。
   已由 `LayeredArchitectureTest` 强制的部分: 非 chat/system 不得依赖 chat; 外部不得依赖 `chat.dto`;
   entity 包内不得定义枚举; `system.entity` 只允许 system 与 aspect。
6. **架构守护**: `LayeredArchitectureTest`(ArchUnit) 固化以上规则, 违反即测试失败; 修改包结构必须同步更新该测试与本文档。
7. **流程与方法的现场文档**: `docs/flow-map.md`(全部执行路径流程图 + 降级总表 + 键空间) 与
   `docs/method-map.md`(逐类逐方法的作用与调用方 + 状态标注汇总) 是改代码前的定位入口。
   改动对话管线/检索/鉴权/缓存链路, 或新增类与方法时**必须同步这两份文档**(尤其 §12 的
   【未被引用】/【仅测试引用】/【已被替代】清单与 §18 降级总表)。

---

## 行为准则

### 1. 编码前先思考 (Think Before Coding)

- **RAG 策略:** 明确"语义向量 + 关键词 BM25 多路召回 → RRF 融合 → 重排"的检索链路。
- **消除歧义:** 用户说"更好的搜索"时，确认是指混合检索、阈值还是重排。
- **技术选型:** 简单查询用 Mapper 方法，不引入多余抽象。

### 2. 极简主义 (Simplicity First)

- **Spring AI:** 优先直接使用 `ChatClient`，不创建多余包装层。
- **MyBatis-Plus:** 优先 `BaseMapper` + `LambdaQueryWrapper`，不手写 XML；只在复杂 SQL 时自定义 @Select/XML。
- **配置(默认值单一来源 = Java):** 各段配置与其默认值只在 `config/props/*Props` 里写一次,
  `application.yaml` 只保留两类键——①环境相关需经环境变量注入的(端点/口令/集合名/模型标签/CORS 来源),
  ②安全基线与成本闸门(`kb-only`/`dev-user-header-enabled`/`seed-enabled`/各类超时与上限)。
  **新增配置项时不要顺手往 yaml 抄一份默认值**(那正是历史上"Java 与 yaml 四处漂移"的成因:
  `model-label`、`semantic-cache.ttl-hours`、`session-title.model-enabled`、`query-rewrite.timeout-ms`
  都各说过两套数字)。改业务调参直接改 props 类的字段初值。
- **严格绑定不许放宽:** `AppProperties` 开了 `ignoreUnknownFields=false`(Boot 4 默认为 true, 会静默忽略
  绑不上的键)。yaml 里出现拼错/已删除的 `app.*` 键时**启动即失败**, 这是刻意的; 若某键天生不该有字段
  (bean 创建前就被 `@ConditionalOnProperty` 消费的装配期开关), 走 `AppProperties.EXEMPT_UNKNOWN_KEYS`
  登记, 而不是关掉严格绑定。
- **单一事实来源(SSOT):** 同一件事禁止在两处独立配置——Qdrant 集合名以 `app.rag.collection-name`
  为唯一事实来源, `spring.ai.vectorstore.qdrant.collection-name` 引用 `${app.rag.collection-name}`;
  新增配置遇到"框架与业务代码各读一份"时, 同样让一方引用另一方。

### 3. 手术式修改 (Surgical Changes)

- **存量代码:** 除非任务需要，不做大规模重构。
- **清理:** 替换旧实现后立即删除废弃类/import。
- **风格:** 统一 Lombok + 构造器注入 (`@RequiredArgsConstructor`)，禁止字段注入；注释密度与内容边界见下方「### 5. 注释与类型选型」。

### 4. 目标驱动 (Goal-Driven Execution)

- **RAG 验证:** 目标应是"针对查询 X 召回 Top-K 相关文档且分数达标"，而非空谈"实现 RAG"。
- **验证:** 优先补单元测试/冒烟脚本验证检索与落库结果。

### 5. 注释与类型选型 (强制)

**注释密度**：类摘要 ≤8 行，只写"是什么 + 必须知道的不变式"。方法的**设计论证、事故复盘、
方案对比**不得写在 javadoc 里——写进 `docs/flow-map.md`(流程/降级) 或
`docs/optimization-roadmap.md`(取舍/演进)，代码里只留一行指针。判据：注释比方法体长即可疑。

**禁止**：同一成员上堆叠两段 javadoc（状态标注须并入摘要段）；`@param x 参数 x` 式同义反复；
把已在文档里的背景整段抄进代码。

**覆盖范围**：所有类必须有类级 javadoc；`public`/`protected` 方法必须有摘要并按需给
`@param`/`@return`/`@throws`；包私有与 private 方法只需一行摘要（说清它保证什么），
无参数语义可省略标签。**豁免**（写了只是噪音，允许不写）：私有防实例化构造器
`private X() {}`、`main` 启动入口、Lombok/record 生成的访问器、`@Override` 且语义已由接口说明的方法。
状态标注统一用 `【未被引用】/【仅测试引用】/【已被替代】` 前缀，写在同一段 javadoc 内。

**record 与 Lombok 类的选型判据**：

| 情形 | 选择 | 理由 |
|---|---|---|
| 映射数据库表的实体 | Lombok 类（`@TableName` + `@Getter/@Setter`） | MyBatis-Plus 结果映射需要无参构造与可写字段；实体还要承载状态迁移（如 `status 0→1→2/3`）与审计填充 |
| 跨边界的**数据快照**：HTTP 出入参、跨模块契约、领域事件、方法的多值返回 | `record` | 构造后不可变：异步审计与并发管线（boundedElastic/虚拟线程）不会读到被中途改写的值；`equals/hashCode/toString` 免样板；缺字段即编译期暴露 |
| 需要部分更新、字段超过 ~8 个、或被继承 | 普通类 | record 加字段等于改构造器签名（所有构造点编译失败），且不能被继承 |

实体禁止包含业务方法；record 允许**无状态派生**方法（如 `effectiveNickname()`、`withFused()`），
这不破坏不可变性。对外契约 record 的字段增删属破坏性变更（Jackson 按构造器绑定），必须同步调用方与文档。
现有分布：42 个 record 全部是 DTO/契约/事件/复合返回值，11 个 `@TableName` 实体全部是 Lombok 类——不要反过来。

---

## 技术标准

### Java 21 & Spring MVC 最佳实践

- **虚拟线程:** `spring.threads.virtual.enabled=true`。
- **分层规范:**
    - **Controller:** 仅请求映射与参数绑定，禁止业务逻辑。
    - **Service:** 业务逻辑 + `@Transactional`，负责实体 ↔ VO 转换。
    - **Mapper:** 各模块 `<module>.mapper` 下接口继承 `BaseMapper<Xxx>`，使用 LambdaQueryWrapper 动态条件。
    - **跨模块契约:** 被其它业务包消费的 Service 必须抽出接口、调用方依赖接口而非实现
      (现有契约: `RagRetriever`←实现 `RagRetrievalService`、`IntentRouter`←实现 `KeywordIntentRouter`、
      `ConversationMemory`←实现 `ConversationMemoryService`、`SemanticCacheAdmin`←实现 `SemanticAnswerCache`)；仅模块内部使用的服务可直接用实现类，不做多余抽象。
    - **对话管线分工:** `chat/service/ChatService` 只做门面(会话校验/并发名额/请求链构建/同步与 SSE 输出编排)；
      前置阶段(标题→改写→短查询扩展→检索→出口判定→缓存查询→审计→装配)统一在 `ChatPreparationService`，收尾阶段(记忆→摘要→审计→
      缓存写入→来源落库)统一在 `ChatCompletionService`，来源文档名提取与"未找到"判定在 `ChatSourceDisplay`。
      **同步与流式必须共用同一份前置/收尾实现, 禁止在任一条管线里复制业务步骤。**

### MyBatis-Plus 实体规范 (Entity Conventions)

- **包路径:** 所属模块的 `<module>.entity`(如 `com.ai.user.entity.SysUser`)。
- **类定义:**
    - `@TableName("xxx")` 显式表名(与 db/schema 脚本一致, 如 `knowledge_document`、`chat_session`、`sys_user`)。
    - 主键 `@TableId(type = IdType.AUTO)`；字段名用 camelCase，开启 `map-underscore-to-camel-case` 自动映射 snake_case 列。
    - 审计字段: `createdAt`(@TableField(fill=INSERT)) / `updatedAt`(@TableField(fill=INSERT_UPDATE))，由 `AuditMetaObjectHandler` 自动填充。
    - 大文本字段直接 `String` + `TEXT` 列；枚举字段用枚举 name 存字符串列(MyBatis 默认 EnumTypeHandler)。
- **禁止事项:**
    - 实体禁止包含业务方法，仅作数据载体。
    - 实体禁止直接返回给前端，必须转 VO (record)。
- **表结构管理:** 由 `db/create_table.sql`(核心5表) + `db/schema-mysql-extra.sql` 通过 `spring.sql.init` 维护(幂等 IF NOT EXISTS)。
- **注释强制:** 每张表必须写表注释(`COMMENT='...'`), 每个列必须写列注释——注释是列语义的唯一现场说明(状态码取值、审计字段口径、脱敏约定等)。`IF NOT EXISTS` 不会给存量库补注释, 存量库用 `db/upgrade/` 下的一次性迁移脚本(最新: `2026-09-semantic-max-score.sql`), 其列定义照抄存量库真实结构、只追加 COMMENT; 新增列同样要补 upgrade 脚本, 并用 `AFTER <前序列>` 对齐新库建表顺序, 免得新库与存量库列序分叉。

### 向量化与 RAG 数据规范

- 入库流程: 上传 -> `knowledge_document(status=0)` -> 异步(Tika 解析 -> TokenTextSplitter 512/100 分块 -> 元数据 doc_id/file_name/chunk_index/collection -> 向量化入库) -> 同步注册 `KeywordIndex`(BM25) -> status=2/3。
- **上传入口校验顺序(禁止跳过)**: **管理员校验(`@RequireAdmin` 切面, 5002/403)** -> 空文件(`FILE_EMPTY` 1005) -> 空文件名(1001) -> 扩展名白名单(1002) -> 单文件大小 ≤50MB(1003) -> 魔数/ZIP炸弹校验(1002) -> 落盘+落库。**批量上传**(`POST /api/knowledge/upload/batch`, multipart 字段 `files`)逐文件独立执行, 单个失败不影响其它, 响应含每文件成败原因; 入库失败(`status=3`)的 `error_message` 必须为友好中文(禁止原始英文异常/堆栈)。
- 向量点元数据需含 `doc_id`/`file_name`/`chunk_index`(删除与溯源依据)；文档删除按 doc_id 过滤检索出点 id 后精确删除，并同步移除关键词索引。
- 检索链路: **短查询扩展**(去空白后 <`app.context.short-query.min-chars` 才触发, 补全成完整检索句)
  -> **一律检索**(RAG/HYBRID 会话, 工具轮除外) -> 混合召回(语义+BM25) -> RRF -> 重排(`score`/`api`/`llm`/`none`)
  -> **相似度阈值过滤** -> Top-K 注入 -> **由检索结果算出出口 `ChatOutcome`**。意图路由不再是链路入口的闸门, 详见 P3-7。
- **阈值判定刻意排在重排之后**(召回层与阈值完全无关): 先过滤会让"余弦分偏低但确实答得上"的候选
  (第 6 名那类)连被重排捞一次的机会都没有——这正是"调试页查得到、对话却答无据"的成因之一。
  出口判据读的是 `semanticMaxScore`(未截断的观察值), 因此**移动过滤时机不改变出口结论**, 只改变注入内容;
  过滤器读 `DocumentMeta.semanticScore`(metadata 里的余弦原始分)而**非 `doc.getScore()`**——
  重排后 score 已被融合分/模型相关度分占用, 读错字段等于阈值失效。
- **三种分数只有一个能判相关性**(强制口径): `SourceVO.semanticScore`=语义路**原始余弦分**(出口判据用的就是它);
  `SourceVO.keywordScore`=BM25 原始分; `SourceVO.score`=重排后的**融合分(相对名次)**。
  融合分在"仅词面命中"时取 `kw/maxKw`, **榜首恒等于 1.0**, 与相关性无关——禁止拿它判断"是否匹配"或写进定标数据。
  它由 `RetrievalCandidate.toDocument` 把两路原始分留进 metadata(`semantic_score`/`keyword_score`)后透出,
  读取口径统一在 `DocumentMeta.semanticScore`(纯语义路直出未融合时 score 本身就是余弦分)。
- **检索整段受 `app.rag.retrieve-timeout-ms`(默认 10s)保护**: 嵌入与向量库是外部网络调用,
  端点挂起会把对话拖住(实测单轮检索卡过 100s)。超时/异常降级为 `RetrievalOutcome.executedEmpty()`
  ——记为 **已执行、零命中**, 而不是 `none()`(未执行); 出口判定据此给 `ANSWERED_OPEN` 而非拒答,
  避免把一次瞬时故障固化成"知识库没有答案"。
- **审计口径(P3-7 变更)**: 旧不变式"`rag_mode=KB` 且 `retrieval_executed=false` ⟺ 语义缓存命中"
  **已作废**——检索现在先于缓存查询执行, 缓存命中时确实执行过检索。缓存命中改由一等公民
  `answer_outcome=ANSWERED_FROM_CACHE` 标识。仍然成立的约定: 缓存命中路径不产生 `context_log`(未装配上下文)。
- **相似度阈值勿随意上调**: `similarity-threshold` 默认 0.45——实测该 embedding 模型分数量级偏低
  (正确 chunk 可能只有 0.5~0.6), 阈值 0.6 会误杀正确答案(案例: "加班调休"0.565 被过滤导致答非所问)。
  调整前必须用 `/api/ai/rag/search` 观察真实分数分布——该接口现在**走与对话同一条链路**
  (短查询扩展 → 检索 → 出口判定), 参数留空即跟随对话配置, 响应直接给出"这轮交给对话会判哪个出口"
  (`RagDebugVO.answerOutcome`), 不再出现"调试页查得到、对话判无据"的口径分叉。
  实测短查询的量级差异很大: 裸词"产品"余弦 0.4097(不过阈值), 补全成"产品的定价和套餐有哪些？"得 0.7014。
  **但该值至今没有分布依据(只是踩了一例反例后定的), 且现有实测语料仅 16 点/约 5KB 不可外推**——
  定标计划见 `docs/optimization-roadmap.md` P3-6; 在它完成前, 禁止把任何"答/拒"判据建在这个阈值上。
- **语义路阈值必须本地过滤, 不得下发向量库**(强制): 下发时低于阈值的分块永不返回, 日志里能看到的分数
  全部 ≥阈值, **分布被从底部截断**——据此定标必然得出"阈值还能再提高"的错误结论。
  `HybridRecaller.recall` 以 0 阈值召回后本地过滤, 并把**过滤前最大分**记入
  `rag_decision_log.semantic_max_score`(P3-6 定标与 P3-7 相关性判据的唯一未截断观察值)。
  分数不可得的实现(`getScore()` 为 null)保留该候选不作判定, 不得因缺分而把整路清零。
- **KeywordIndex 重启即空**(进程内 BM25): 语义阈值下调可缓解, 但"词面强匹配"场景仍需 BM25 路;
  启动时自动重建已实现(见 roadmap `P3-KeywordIndex 自动重建`, 2026-09-16)。
  ⚠ **勿把 BM25 命中数当相关性信号**: 实测它对"今天天气""写一首诗"这类问题同样返回 10 条并填满 topK,
  小语料下恒有命中——相关性判据只能建在向量路过阈值上(见 roadmap P3-7)。
- 每次对话的意图路由/检索决策自动落库 `rag_decision_log`(见 `/api/system/rag-decisions`)。
- **意图路由三态的职责已大幅收窄(P3-7)**: `RagMode` = KB / TOOL / GENERAL 仍是枚举, 但
  **只用于工具类的成本短路**(跳过一次必然无用的知识库检索), **不再决定"这个问题要不要查知识库"**。
  作答口径一律由检索结果算出的 `ChatOutcome` 决定。旧版本文档把"由词表决定是否检索"当成设计,
  其后果是"知识库内容能否被问到取决于有人记得改词表", 已实测证伪(售后文档在库却答"未找到")。
  - 判定顺序: 命中 `app.rag.tool-keywords`(订单/物流/快递/包裹等, 含少量同义词) → TOOL, 跳过知识库检索
    (答案在业务库, 检索必然查不到反而挤占上下文预算); 其余(RAG/HYBRID 会话)**一律执行检索**。
  - `app.rag.internal-keywords` **已退出正确性路径**: 它不再决定内容可否被问到, 仅影响审计里的
    `rag_mode` 诊断标签。上传新文档**不再需要同步改词表**(这是 P3-7 的主要收益)。
  - 工具问题**不入语义缓存**(答案随实时数据变化, 缓存会串味): 出口记 `TOOL_DATA`, 收尾按出口
    与 `toolCalls` 双重排除写缓存。
  - **单轮工具调用次数上限(B2)**: `ToolCallLogAspect` 复用 `toolCalls` 计数,
    超过 `app.chat.max-tool-calls-per-turn`(默认 8, 0=关闭)不执行工具、直接抛 `TOOL_CALL_LIMIT(6009)`,
    Spring AI 回传模型令其收尾作答——防模型异常时无限连环调工具烧 Token。
  - **意图路由结果缓存已删除**(P3-7 B 批): 旧 `CachingIntentRouter`(@Primary 装饰器)把"问题→路由结果"
    缓存进 Redis(`rag:intent:v{version}:{sha256}`, TTL 60 分钟, 另有 `IntentCacheAdmin` +
    `DELETE /api/system/intent-cache` 版本自增失效)。**删除理由**: 它缓存的判定在 A 批后只剩"是否工具轮",
    而底层是对 69 个人工关键词(内部词表 58 + 工具词表 11, 见 `config/props/RagProps`)做内存 `contains` 扫描(微秒级、确定性), 每次判定反而多付两次 Redis 往返;
    并且它有一致性隐患——版本号跨重启存活, 改词表而忘记调失效接口时旧结论会在 TTL 内继续生效。
    **不要再为路由结果加缓存**: 判据已换成检索分数, 缓存预判既无收益也会让审计里的"预判 vs 出口"对照失真。
     Redis 不可用时的降级口径从此少一项(见"Redis 使用与降级约定")。
  - 演进规划: 后续可在规则引擎之上叠加"意图检索"(embedding 语义召回, 替代纯关键词泛化)与
    "LLM Judge 纠偏"(低置信二次确认), 见 `docs/optimization-roadmap.md` 路由分层方案。

### 对话流水线与 SSE 事件契约

- 流式接口 `/api/ai/chat/stream` 输出**类型化事件**(ServerSentEvent.event 字段)：
  仅 `content`(正文增量); 结尾 `data:[DONE]`(无 event 头, 兼容契约)。
  不推送阶段提示/思考流/来源事件——**引用来源不再返回前端(2026-09 契约变更)**,
  只落库 `chat_log.sources`(审计走系统日志接口)。
- 意图路由关键词表(缺省含年假/报销/放假/节假日/中秋等)决定是否检索。
  ⚠ **旧表述纠正**: 本节曾写"语料外问题返回'未找到'属正确行为"——该定性掩盖了一个真实缺陷:
  **知识库内容能否被用户问到, 取决于有没有人记得去改一份与文档内容无关的配置**。已实测后果——
  `售后服务流程规范.pdf` 在库, 但"售后/退货/保修"均不在词表 → 判 GENERAL → 检索整段跳过 → 答"未找到",
  而检索调试页(不走路由)能查到。静默失效 + 默认开启 = 设计缺陷, 不是正确行为。
  改造方案与触发条件见 `docs/optimization-roadmap.md` P3-7(前置 P3-6 阈值定标)。
- 前置阶段(改写/检索/装配)在 boundedElastic 执行, 各阶段耗时必须打 INFO 日志(改写 ms/缓存查询 ms/前置 ms/总 ms)——
  端点延迟标定的数据来源, 禁止删除。**缓存命中分支同样要打耗时**(该分支不打"前置阶段完成",
  曾因缺日志无法定位一次 16.8s 的缓存路径卡顿)。
- **流式请求索取 usage**: `streamUsage(true)` 只在 stream 请求注入(`app.chat.stream-include-usage`,
  默认开)——OpenAI 兼容端点流式默认不返回 usage, 不索取则 `chat_log.total_tokens` 恒为 NULL,
  前端走的流式主流量在成本审计里会全空白; 同步请求不注入(部分端点拒绝 stream_options 与 stream=false 并存)。
- qwen3 类模型思维链控制: `extraBody(enable_thinking)` 按调用注入——改写/摘要/主对话**全部默认关闭**
  (`app.chat.disable-thinking=true`)。**主对话必须关闭思维链**的两条硬理由(2026-09 线上故障实证):
  ① qwen3 思考模式长推理时可能**整段静默不出字 >60s**, 撞上 OpenAI 客户端 okhttp 60s read timeout
  被重置(`InterruptedIOException + StreamResetException: CANCEL`), 整轮回答只剩一条中断提示;
  ② 关闭后总耗时从 10~60s 降到 2~6s, 首字秒级。若确需展示思考过程置 false, 同时把
  `stream-idle-timeout-ms` 调大或关闭以容忍长思考静默。
- **流式静默超时保护**: `app.chat.stream-idle-timeout-ms`(默认 20s)——相邻增量间隔超过该值即主动
  终止并降级提示, 早于 okhttp 60s read timeout 触发; `spring.ai.openai.timeout`(120s)仅作二次保险。
- **流式中断不写语义缓存**: 中断/静默超时走 `ChatCompletionService.completeInterrupted`(只写记忆与
  审计), 禁止走 `complete()`——后者会把"中断提示"当答案缓存, 下次同问直接命中一条 19 字提示。

### Redis 使用与降级约定(强制)

- **现有 4 个使用点**(全部经 `StringRedisTemplate` 直连, 项目**不用** Spring Cache 抽象/spring-session):
  `RedisLoginAttemptLimiter`(`login:fail:*`/`login:lock:*`) · `SessionCacheService`(`session:meta:*`/`session:absent:*`)
  · `SemanticAnswerCache`(`rag:answer:*`/`rag:miss:*`/`rag:kb:version`)
  · `TokenBlacklistService`(`jwt:black:*`)。键前缀必须沿用, 便于运维 grep 与按前缀清理。
  (第 5 项 `CachingIntentRouter`/`rag:intent:*` 已随 P3-7 B 批删除, 见上一节。)
- **读写两侧都必须自行 catch 并降级, 绝不允许 Redis 故障冒成业务 500**(`RedisConnectionFailureException`
  是 `DataAccessException`, 一旦漏出就被 `GlobalExceptionHandler` 兜底成 5001/HTTP 500——登录限流读侧
  曾犯此错, 有真实探针证据)。新增 Redis 使用点时必须同时覆盖读路径与写路径。
- **降级必须看得见**: catch 里一律 `log.warn`, **禁止 DEBUG**(生产级别是 info, 等于静默), 且必须经
  `common/WarnThrottle` 节流(默认 60 秒一条并汇总被抑制条数)——旁路组件故障时每请求触发多处降级,
  不节流会刷屏。声明为字段初始化器(`WarnThrottle.of(log)`), 不进构造器参数。
- **启动自检**: `config/RedisReadinessProbe`(ApplicationRunner)探测一次并输出"哪些能力处于降级态"。
  探针**只打日志、绝不外抛**, 不得影响启动。
- **生产基线**: `app.auth.blacklist-fail-open` 必须 false(prod yaml 已设), 由 `SecurityConfigValidator`
  在 prod 启动期强制(配成 true 直接拒绝启动)。登录限流则**刻意** fail-open(不提供拒绝开关)——
  拒绝会把基础设施故障放大成整站无法登录。
- **不用 Redis 的地方**(刻意保持进程内, 勿"顺手分布式化"): `Timeouts` 的并发 `Semaphore`(保护的是本进程
  连接/线程预算)、`PromptService` 模板缓存(classpath 静态)、`ChatClientProvider` 惰性构建(一次性)、
  `KeywordIndex` 读写锁(本地结构; 需要跨实例一致时走"变更广播"而非把索引搬进 Redis)、
  MyBatis 二级缓存(制度文档更新场景脏读风险高)。
- 配置项在 `application.yaml` 的 `app.auth.*` 显式声明(`rate-limit-backend`/`blacklist-fail-open`);
  连接参数 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`。**Redis 不在 docker-compose 内**, 需自行启动。

### 语义缓存与审计不变式

- 缓存命中时**跳过上下文装配与模型调用**(P3-7 起检索已先跑过, 不再能跳过检索): 同步接口整段返回, 流式只发 **1 个** content 事件
  (不是逐 token 增量)——前端/测试断言"流式增量≥2"必须在冷缓存下进行。
- 审计约定(P3-7 起): 缓存命中由 `rag_decision_log.answer_outcome=ANSWERED_FROM_CACHE` 显式标识
  (不再靠 `rag_mode`+`retrieval_executed` 反推), 且该轮**不会**产生 `context_log`(未装配上下文)。
  出口共五个值, 语义见 `com.ai.rag.ChatOutcome`。
- 缓存键 = 知识库版本号 + 对话模型标识 + 归一化问题 SHA-256, 形如
  `rag:answer:v{版本}:m{模型}:{摘要}`(负缓存 `rag:miss:` 同命名空间)。两个失效维度:
  文档上传/删除/重处理 → 版本自增失效; **切换对话模型** → `m{模型}` 段天然形成新命名空间
  (旧模型的回答不再被命中, 随 TTL 淘汰), 模型名由 `ChatClientProvider.modelLabel()` 单点解析
  (与 `chat_log.model_name` 同一口径, 禁止在别处再解析一遍)。
- **缓存条目是跨用户共享的**(公司知识库同一问题答案一致), 因此写入侧必须排除一切含个性化/
  实时数据的回答: ① 经过改写的提问(`cacheEligible` 已排除); ② **本轮调用过工具**的轮次——
  答案可能含员工电话/订单状态, 计数经 toolContext 的 `toolCalls`(AtomicInteger) 由
  `ToolCallLogAspect` 自增, `ChatCompletionService.complete` 据此跳过正/负缓存写入并打 INFO 日志。
  新增工具或让工具在 KB 轮次参与作答时, **不要**放宽这条闸门。
- 运维清空: `DELETE /api/system/semantic-cache`(`@RequireAdmin`)→ 返回失效后的版本号。
  契约 `SemanticCacheAdmin`, 实现在 `SemanticAnswerCache`。

### 查询改写与短查询扩展

- 改写基于 Spring AI 内置 `CompressionQueryTransformer`(spring-ai-rag 模块, 包名
  `org.springframework.ai.rag.preretrieval.query.transformation`), 把"最近 6 条历史 + 当前追问"
  压缩为独立检索查询; 不再使用自维护的 query-rewrite.st 模板。
- **指代词启发式**(`reference-hint-required=true`): 问题不含 `reference-hints` 线索词(那/它/他/这个…)时
  跳过改写直接检索(完整问题零等待); 含线索词才调 LLM 改写。
- 改写调用注入 `enable_thinking=false`(disable-thinking=true, 实测 10~29s→0.6s)。
- 改写同步执行于提问路径, 受多重保护: 超时(`query-rewrite.timeout-ms`, 默认 30000)、
  **熔断**(连续失败≥2 次进入 60s 冷却, 冷却期内零等待回退原问题, 成功即复位)、结果等同原问题视为未改写。
- 每次调用记录实际耗时(成功 INFO / 失败 WARN), 禁止删除——这是端点延迟标定的数据来源。
- **短查询扩展(`ShortQueryExpander`, 改写之后、路由之前执行)**: 去空白后长度 <
  `app.context.short-query.min-chars`(默认 6)的提问, 用一次带超时的模型调用补全成完整检索句
  (超时/失败/空结果/与原句相同一律回退并 WARN 节流, 绝不影响对话)。
  存在的理由是实测的分数断层: 裸词"产品"余弦 0.4097 判无据, 同一分块用完整句问得 0.52~0.70。
  - 提示词必须**补出该词最可能指向的 2~3 个具体方面**, 不能只把句子补完整——
    实测"产品是什么？"这种空泛补全反而降到 0.3503, 比不扩展更差(`prompts/short-query-expand.st` 已按此约束写)。
  - AGENT 会话不扩展(靠工具作答); 扩展调用同样注入 `enable_thinking=false`。
  - **扩展成功 = 该轮"已改写"**: `ChatPreparationService` 把 `RewriteResult.rewritten` 置 true,
    语义缓存的读写据此全部排除。理由与工具轮禁写缓存同源——缓存条目跨用户共享,
    拿扩写词检索出的答案挂到原始短词键("产品")上, 后来者会拿到按别的意图扩出来的内容。
  - 决策日志的 `user_message` 仍记用户原话, 扩展后的问题只进检索与调试响应
    (`RagDebugVO.retrievalQuery` + `expanded` 标记), 便于排查时区分两者。

### Spring AI 内置组件采用策略(2026-09 评审结论)

- **已采纳内置**:`CompressionQueryTransformer`(查询改写)·`spring.ai.retry.*`(模型调用重试,
  max-attempts=2 只补瞬态失败, 与业务超时/熔断互补)·`SimpleLoggerAdvisor`(DEBUG 级打印 prompt/响应, 调试用)·
  TikaDocumentReader+TokenTextSplitter(文档 ETL)·ChatClient/@Tool(基础装配)。
- **保持自研(无内置或自研更强)**:滚动摘要(无内置组件)·Token 预算四段装配(ContextAssembler,
  augmenter 模板做不了程序化截断)·混合检索 RRF+融合重排(2.0 的 postretrieval 仅有接口无实现)·
  意图路由(无内置)·短查询扩展(内置 `MultiQueryExpander` 要 N 次检索且不改"空泛补全反而降分"这个问题)。
- **可选路径(未迁移, 结论备查)**:
  - 官方 `spring-ai-starter-model-chat-memory-repository-jdbc`:SPI 与自研 DbChatMemoryRepository 同构
    (findConversationIds/findByConversationId/saveAll/deleteByConversationId), 支持 MySQL 方言与
    initialize-schema 自动建表; **差异/迁移成本**: 官方表多一列 `sequence_id`(我们的表需加列迁移)、
    旧 content 数据兼容、需处理 ChatConfig 装配调整。功能等价, 建议需要多数据库或想卸维护包袱时再迁移。
  - `RetrievalAugmentationAdvisor` 五段式编排: queryTransformers 可直接挂我们的 CompressionQueryTransformer,
    但 Token 预算四段切分塞进 queryAugmenter 很别扭, RRF 融合/重排仍需自研 DocumentPostProcessor——
    待需要标准 RAG 流(如 MultiQueryExpander)且端点延迟可接受时再评估。

### Token 计量与预算

- 默认实现 `JtokTokenCounter`(jtokkit CL100K_BASE 精确计数, Bean 在 ContextConfig)；
  截断 `truncateToTokens` 为"编码→取前 N 词元→解码"的精确还原, 不再依赖估算迭代。
- CL100K_BASE 对 qwen 系私有分词器为**近似**(中文约 1~2 词元/字), 方向偏保守(计数偏高),
  用于预算控制安全; 需绝对精确可注册 qwen 官方分词器实现同类型 Bean 覆盖。
- 消息开销口径固定为每条 +4(角色/分隔符), 与 `HeuristicTokenCounter` 保持一致;
  修改预算/阈值语义前必须同步该口径, 否则历史与 RAG 段预算会漂移。
- `HeuristicTokenCounter` 保留为备用实现与测试对照(零依赖, 偏保守估算)。

### 会话历史与滚动摘要

- 滚动摘要**异步执行**：每轮对话写回记忆后由 ChatService 调用
  `ConversationMemory.summarizeIfNeededAsync(sessionId)`, 满足触发条件(条数超
  `app.context.summary.trigger-messages`, 或 Token 超 `trigger-tokens`, 0=关闭)时在后台
  合并既有摘要生成新摘要并裁剪原始历史; 请求路径(loadHistory)禁止任何 LLM 调用。
- **@Async 一律显式指定执行器名**: 文档入库=`ingestionExecutor`(AsyncConfig), 审计落库/滚动摘要=
  `auditExecutor`(AsyncConfig)。禁止裸 `@Async`——context 里有多个 TaskExecutor 时 Spring 会在
  "无名为 taskExecutor 的 bean" 上报歧义(仅告警但行为悬而未决), 显式指定名字可消除。
- **会话自动标题(两段式, `SessionTitleService`)**: 首轮在 `ChatPreparationService.prepare` 最前
  **同步**写截断兜底标题(`app.session-title.fallback-chars`), 再把模型精修提交到
  `sessionTitleExecutor`——精修**只需问题不需答案**, 因此与本轮检索/回答全程并行, 前端在本轮
  结束后 `GET /api/sessions/{id}` 刷一次即拿到精修版。三条规则缺一不可:
  ①抢首轮必须用**条件更新**(`WHERE title IS NULL OR title=''`)并靠影响行数判归属, 禁止"先读再写"
  (用户快速连发两轮时只有一个请求能触发精修); ②精修回写必须带"标题仍等于我写过的那个兜底值",
  否则**人工改名会被自动流程覆盖**(`PUT /api/sessions/{id}/title` 是人工入口, 之后不再被改);
  ③两次写库都必须 `SessionCacheService.evict`——`requireActive` 读的是 Redis 里的会话实体副本。
  降级: 模型未配置/超时/失败/清洗后为空一律保留兜底标题并 WARN, 绝不影响对话。
  **标题精修的线程池刻意用 `AbortPolicy`(队列满即拒绝)而非其它池的 `CallerRunsPolicy`**——
  后者会在队列满时把几秒的模型调用放回**对话请求线程**执行, 而丢弃精修的代价只是"标题没那么好看"。
- 摘要未就绪时装配走"窗口历史 + Token 预算"兜底, 不丢上下文; 摘要失败保留现状, 下轮写回后再触发。
- **计数用数据库 COUNT(*), 禁止加载全文再取 size:** 判断"历史是否够一轮改写"走
  `ChatMemoryCounter.countByConversationId`(每轮都调一次, 旧实现 `findByConversationId().size()`
  会把全部历史反序列化, 开销随历史长度线性增长)。
- 同会话的 append/摘要/裁剪共用 per-session 锁与防重入标记, 防止并发覆盖丢消息。

### 用户与鉴权

- 注册/登录: `/api/auth/register`、`/api/auth/login`，密码 PBKDF2 加盐哈希(`PasswordHasher`)。
- **登录限流的降级口径(读写一致, 强制)**: `RedisLoginAttemptLimiter` 读写两侧都必须自行捕获 Redis 异常——
  读侧 `isLocked`/`remainingLockMs` 异常时按"未锁定/0"放行并打 WARN, 写侧 `fail`/`del` 同样吞异常。
  **禁止**让 `RedisConnectionFailureException` 冒到 `GlobalExceptionHandler`(否则"限流存储故障"变成"登录接口
  500/5001", Redis 挂 ⇒ 整站无法登录); 也不引入"故障即拒绝"开关(会把基础设施故障放大成 DoS)。
  代价(故障期无防护)由 WARN 暴露。另: 进程内实现 `InMemoryLoginAttemptLimiter` 的过期条目由
  `recordFailure` 在条目数超过 `EVICT_THRESHOLD`(1000) 时顺带清理——**必须保留该门控**, 无阈值则每次
  失败全表扫, 撞库(大量不同用户名)时退化成 O(n²) 自伤; 阈值不放宽到"每次都扫"。
- 角色: `sys_user.role`(ADMIN/USER, 默认 USER), 登录时写入 JWT `role` claim; `UserContext.CurrentUser.isAdmin()` 判定。
- 授权: "管理员或本人"类查询用 `@RequireSelfOrAdmin(userIdParam = "...")` 注解 + `SelfOrAdminAspect` 切面统一强制改写 userId 参数, 数据隔离由 Service 层 userId 过滤完成; 越权/无角色返回 `AUTH_FAILED`(5002, HTTP 403)。
- 管理动作: `@RequireAdmin` 注解 + `SelfOrAdminAspect.enforceAdmin`——仅 ADMIN 放行; 已挂知识库**上传 / 批量上传 / 删除 / 重处理**(凡能改动全公司共享 RAG 内容的操作一律管理员; 任何登录用户可上传 = 能往同事的答案里塞任意材料)。新增管理类操作时同样挂载。
  ⚠ **注解标在 Service 方法上，而 Spring AOP 不拦截自调用**: `uploadBatch` 内部直调 `this.upload(...)`，
  只标 `upload` 会让批量入口成为绕过校验的后门，且越权的 5002 会被批量循环的
  `catch (BusinessException)` 降级成"每个文件失败但 HTTP 200"。因此**同一批写方法必须各自都带注解**
  (已由 `LayeredArchitectureTest#knowledgeWritesShouldRequireAdmin` 钉住，新增写方法漏标即测试失败)。
- 日志归属: 四张日志表均含 `user_id`(tool_call_log 经 Spring AI `toolContext` 透传回填), 查询接口一律按"管理员或本人"过滤。
- JWT: `JwtTokenProvider` 签发 HS256；请求带 `Authorization: Bearer <token>`。
- **凭据规则(强制, 因仓库将公开)**: 仓库内**不得**出现任何真实或示例口令/密钥的默认值——
  `DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`、`DASHSCOPE_API_KEY` 一律走环境变量，本地则写
  **gitignore 的 `application-local.yaml`**(模块根目录, 由 `spring.config.import: optional:file:./...` 加载;
  键名写成顶层 `DB_PASSWORD` 即可被 `${DB_PASSWORD:}` 占位符解析, 不涉及覆盖顺序问题——这是刻意选型,
  使两份配置谁先加载都不影响结果)。模板 `.env.example`。**`.env` 只服务 `docker compose`,
  Spring Boot 不读它**；IDEA 运行配置也**不继承** shell 的 `export`——这两条都曾导致"改完默认值后本地启动失败",
  改凭据注入方式时必须同时验证 IDE 里的点启动, 不能只验命令行。`jwt-secret` 留空时: 非生产由 `JwtTokenProvider` 生成一次性随机密钥(重启即失效, 仅限本地),
  **生产留空/过短/含占位符特征(`change-me`/`dev-secret`/`your-`/`replace-me`)则由
  `SecurityConfigValidator` 拒绝启动**。演示管理员口令来自 `app.demo.admin-password`
  (播种**默认全局关闭** `app.demo.seed-enabled=false`, 且 `admin-password` 无默认值——留空时 `DemoDataInitializer` 跳过建号并 WARN, **绝不退回任何写死口令**; 首个管理员由注册后置 role=ADMIN)。`docker-compose.yml` 的端口**只绑 `127.0.0.1`**
  (Qdrant 无 API Key 时暴露到局域网等于开放读写向量库)。
- 拦截器 `AuthInterceptor` 保护 `/api/**`，通过 `UserContext` 暴露当前用户；`X-User-Id` 模拟登录默认关闭, **且仓库内无任何配置文件打开它**(dev profile 文件已删除)——本机需要时只能显式传 `--app.auth.dev-user-header-enabled=true`, 严禁在生产这样启动。

### 统一返回值格式规范

- 所有 Controller 返回 `Result<T>` 或 `Result<PageResult<T>>`，禁止直接返回 Entity。
- `Result` 结构: `{code, message, data, timestamp}`；`PageResult`: `{records,total,page,size,totalPages}`。
- 错误码集中定义在 `ErrorCode`（按枚举实测）：文件与上传 1001~1005、文档 2001~2002、会话与检索 3001~3002、
  参数 4001、系统与 AI 5001~5004（含 5002 授权/越权）、用户与登录 6001~6006、并发限制 6010（6007~6009 预留）。
  业务异常抛 `BusinessException(ErrorCode, message)`，由 `GlobalExceptionHandler` 统一兜底，禁止向客户端泄漏堆栈。

### Spring AI & RAG 提示词

- 提示词外部化到 `classpath:/prompts/*.st`(base-system/rag-context/general-system/kb-only-system，另有非 system 用途的 short-query-expand.st 短查询补全模板)。
- 注入上下文前必须经重排或相似度阈值过滤。
- **提示词选择矩阵**(`PromptService.systemFor`, 唯一选择点——同步与流式共用)：

入参是 **`ChatOutcome`(检索后算出的出口)**, 不是意图预判——这是 P3-7 的落点。默认 `kb-only=true`。

| 出口(`ChatOutcome`) | `kb-only=false`(宽松) | `kb-only=true`(默认, 严格) |
|---|---|---|
| `ANSWERED_FROM_KB` / `ANSWERED_FROM_CACHE` | `base-system.st` + `rag-context.st`(`{{extraRule}}`=可补充常识) | `kb-only-system.st` + `rag-context.st`(`{{extraRule}}`=禁止引入资料之外的知识, **且要求先答资料覆盖的那部分**) |
| `REFUSED_NO_EVIDENCE` | —— 该出口只可能在严格模式出现 | `kb-only-system.st`(按固定口径友好拒答) |
| `TOOL_DATA` | `general-system.st` | `kb-only-system.st`(第 4 条仍允许并只认工具返回值) |
| `ANSWERED_OPEN` | `general-system.st`(自由作答) | `general-system.st`(非检索会话/检索降级) |
| `AGENT` 会话(任意出口) | `base-system.st` | `base-system.st`(**刻意不变**: Agent 型本就靠工具) |

- **`TOOL` 必须跟着切换**(易踩点): 宽松模式下 TOOL 与 GENERAL 共用 `general-system.st`, 而该模板首句就授权
  "可以回答常识、科普、闲聊类问题"。若严格模式只改 GENERAL, "只允许知识库作答"会被 TOOL 分支绕过,
  开关等于无效。
- **默认 `kb-only=true`。它是提示词级软约束, 不是硬保证**(刻意如此, 勿在文档/沟通中把它说成"保证不编造"):
  模型仍被调用, 长多轮或资料字面相关而语义不对题时仍可能拼出看似有据的答案。需要零编造时正解是
  **硬闸门**——在 `ChatPreparationService` 检索零命中分支直接返回固定文案、不调模型; 与本开关不冲突, 可叠加。
- 严格模板的拒答措辞与 `ChatSourceDisplay.NO_RESULT_ANSWER`(语义负缓存命中时的固定回答)必须逐字一致;
  因 `prompt` 模块禁止依赖 `chat` 模块(ArchUnit 规则 5), 两处不能共享常量, 由
  `PromptServiceTest#strictRefusalSentenceMatchesNegativeCacheConstant` 钉住, **改措辞时两处同改**。

---

## 交付规范 (强制)

### 1. 变更必须给出精确映射, 禁止只说"已完成"

每次改造完成后, 交付说明必须包含**逐项映射表**, 让用户能直接定位变化:

| 位置(文件:行) | 旧 | 新 | 性质 |
|---|---|---|---|
| `ContextConfig.java:27` | `return new HeuristicTokenCounter();` | `return new JtokTokenCounter();` | 替换(Bean 切换点) |
| `pom.xml:95-99` | 无 | `com.knuddels:jtokkit:1.1.0` | 新增依赖 |
| `JtokTokenCounter.java`(新) | 不存在 | 精确计数/精确截断实现 | 新增类 |

并且必须显式列出**哪些文件完全没有改动**(证明影响面受控), 以及**谁在调用新实现**(调用方清单)。

### 2. 代码中必须就地标注状态

- **已被替代**的实现: 类/方法 javadoc 加 `【已被替代】` 段落, 写明替代者(带 {@link})与保留原因;
- **未被引用**的方法: 加 `【未被引用】` 段落, 写明无调用方与可否删除;
- **仅测试引用**的方法: 加 `【仅测试引用】` 段落, 写明生产用途被谁取代;
- 前缀统一用 `【】` 中文方括号, 便于全项目 grep: `grep -rn "【已被替代】\|【未被引用】\|【仅测试引用】" src/`。

### 3. 结论必须经自测验证

交付前必须实跑验证(单测 + 运行时), 并在说明中给出**可复现的证据**(命令、接口返回、日志/落库关键值),
禁止以"应该可以/理论可行"作为交付依据。

### 4. 每次完成修改必须本地提交

- **铁律**: 每一批修改完成后(验证通过、说明给出), 立即 `git add -A && git commit`——
  禁止把改动长期悬在工作区。提交信息用中文, 首行概括, 正文列关键点与验证结果。
- **本地提交即可, 不推送远程**(除非用户明确要求); 提交前自查 `.gitignore` 生效、
  无密钥/个人端点/构建产物被误提交(`grep` 敏感字面量)。
- 一条新规则本身也是一次修改: 改完文档/规范同样按此提交。

---

## 后续优化路线

- 性能/安全/成本优化的待评估项（P1 性能成本 / P2 安全加固 / P3 架构级）统一登记在
  `docs/optimization-roadmap.md`——含每项的现状实证、方案、验收标准与触发条件；
  实施前先读该文档, 完成后在"已完成记录"补行。
- P0/P1/P2 与 2026-09-15 那批 P3 已全部落地, 明细与验收证据见 `docs/optimization-roadmap.md` 已完成记录。
  **但 `P3-6`(阈值定标)、`P3-8`(联网搜索)、`P3-9`(入库去重)、`P3-10`(融合重量纲与向量点缺 doc_id)仍是「待评估」项, 尚未实施**——
  `P3-7`(预判下线 + 出口判定 + 意图缓存下线)与"检索口径统一 + 短查询扩展"已于 2026-09-22 落地。
  不要把"已全部落地"理解成 P3 目录已清空。
  关键架构变化: 登录限流契约化(`LoginAttemptLimiter` 接口, Redis 实现默认/memory 可回退,
  `app.auth.rate-limit-backend` 切换); 会话查询走 Redis 缓存(`SessionCacheService`, 异常降级 DB);
  记忆写回 append-only(`ChatMemoryAppender`, 严禁回退"全删全插"); KeywordIndex 用读写锁(avgLen 增量);
  Token 用量已采集落 chat_log.total_tokens; 上传有魔数校验; 工具日志脱敏; 日志按保留期定时清理;
  语义缓存已实施(`SemanticAnswerCache`: 仅缓存未改写/未扩展且出口为"知识库作答"的回答,
  知识库文档变更即版本失效; P3-7 起命中只跳过装配与模型调用, 检索已先行; 知识库上传/删除/重处理
  代码必须调用 `semanticAnswerCache.evictAll()`)。

## 测试策略

- 单元测试使用 Mockito Mock `ChatModel`/`EmbeddingModel`，禁止调用真实 API。
- 集成/冒烟优先真实环境脚本(`docs/seed/e2e_test.py`)；断言含返回文本与落库记录。
- **e2e 必须在冷缓存基线运行**: 语义缓存持久在 Redis, 上一轮写入的回答会让本轮
  "流式增量(content≥2)"与"上下文装配日志"断言失败(缓存命中不装配、只发 1 个 content 事件)。
  脚本段 1 已用管理员接口 `DELETE /api/system/semantic-cache` 建立冷基线, 新增断言不得依赖残缺缓存状态;
  审计类断言(chat_log/context_log/rag_decision_log)为异步落库, 计数前需留出等待。
- 数据库相关用例依赖 MySQL + sql init 脚本建表(需本地/容器 MySQL 可用)。

## 常用命令

- **本地一次性准备:** 模块根目录建 `application-local.yaml`(已 gitignore)写 `DB_PASSWORD: "你的口令"`——
  这一条同时服务于 IDEA 点启动与命令行, 之后不必再管环境变量。`cp .env.example .env` **只为 docker compose 服务**。
- **开发:** `mvn spring-boot:run` 就是安全基线(不建任何账号、模拟登录头关闭)。本机要 `X-User-Id` 模拟登录时显式传参: `mvn spring-boot:run -Dspring-boot.run.arguments=--app.auth.dev-user-header-enabled=true`——**仓库内已删除 `application-dev.yaml`, 不提供任何"一键打开危险开关"的文件**。
  在 IDEA 里直接 Run 同样可用(靠 `application-local.yaml`)。`JWT_SECRET` 本地可留空(自动随机密钥)。
- **首个管理员:** 播种默认关闭(`app.demo.seed-enabled=false`)且口令无默认值 → 用 `/api/auth/register` 注册后 `UPDATE sys_user SET role='ADMIN'`, 再重新登录(角色在 JWT claim 里)。演示业务数据走 `docs/seed/seed-mysql.sql`。
- **测试:** `mvn test`（`AiAgentApplicationTests` 连本地 MySQL; 有 `application-local.yaml` 即可, 否则需 `DB_PASSWORD=<口令>`）
- **清理:** `mvn clean`
- **基础设施:** `docker compose up -d` (Qdrant/MySQL)。**Redis 不在 compose 内**, 需本机自行启动
  (默认 `localhost:6379`, 可用 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` 覆盖)——登录限流、会话缓存、
  语义缓存、令牌黑名单都用它; 不可用时各侧按上述降级口径放行, 不影响登录与对话。
- **建表:** 由启动期 `spring.sql.init` 自动执行(db/ 下脚本)。
- **测试后必须停后端:** 每次运行/验证结束立即停止后端进程并确认 9090 端口已释放——
  Git Bash 的 `kill` 常杀不死 Windows 进程, 需用
  `netstat -ano | grep :9090` 找 PID 后 `taskkill //F //PID <pid>`; 残留实例会占住端口,
  导致下次启动失败或验证跑到旧代码。
