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
├── config        全局配置层: AppProperties · 异步池 · ChatClient 装配 · MVC/跨域 · MyBatis-Plus 装配 · 种子数据
├── prompt        提示词模板装配(PromptService, classpath:/prompts/*.st)
├── rag           RAG 能力模块: 模块根=对外契约(RagRetriever/IntentRouter/RagMode/RetrievalOutcome)
│   └── service   实现(RagRetrievalService/KeywordIntentRouter/KeywordIndex)
├── context       上下文管线模块: 模块根=契约(ConversationMemory/HistoryContext/AssembledPrompt/ContextComposition)
│   ├── entity    ConversationSummary      ├── mapper  ConversationSummaryMapper
│   └── service   ContextAssembler/ConversationMemoryService/ConversationSummarizer/QueryRewriter
├── chat          对话模块: controller/ChatController · service/ChatService · event/审计事件 · dto
├── knowledge     知识库模块: controller/service/entity/mapper/dto
├── user          用户鉴权模块: controller/service/entity/mapper/dto
│   └── security  JwtTokenProvider · UserContext · AuthInterceptor · PasswordHasher · RequireSelfOrAdmin
├── session       会话模块: controller/service/entity/mapper/dto
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
5. **跨模块调用**: 优先走模块根契约接口或对方 `service` 允许跨模块引用 entity/mapper(现状), 禁止引用其它模块的 `controller`。
6. **架构守护**: `LayeredArchitectureTest`(ArchUnit) 固化以上规则, 违反即测试失败; 修改包结构必须同步更新该测试与本文档。

---

## 行为准则

### 1. 编码前先思考 (Think Before Coding)

- **RAG 策略:** 明确"语义向量 + 关键词 BM25 多路召回 → RRF 融合 → 重排"的检索链路。
- **消除歧义:** 用户说"更好的搜索"时，确认是指混合检索、阈值还是重排。
- **技术选型:** 简单查询用 Mapper 方法，不引入多余抽象。

### 2. 极简主义 (Simplicity First)

- **Spring AI:** 优先直接使用 `ChatClient`，不创建多余包装层。
- **MyBatis-Plus:** 优先 `BaseMapper` + `LambdaQueryWrapper`，不手写 XML；只在复杂 SQL 时自定义 @Select/XML。
- **配置:** 保持 `application.yaml` 整洁，只保留用到的配置。

### 3. 手术式修改 (Surgical Changes)

- **存量代码:** 除非任务需要，不做大规模重构。
- **清理:** 替换旧实现后立即删除废弃类/import。
- **风格:** 统一 Lombok + 构造器注入 (`@RequiredArgsConstructor`)，禁止字段注入；**每个方法都要写注释说明作用与参数内容(含 @param/@return/@throws)**。

### 4. 目标驱动 (Goal-Driven Execution)

- **RAG 验证:** 目标应是"针对查询 X 召回 Top-K 相关文档且分数达标"，而非空谈"实现 RAG"。
- **验证:** 优先补单元测试/冒烟脚本验证检索与落库结果。

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
      `ConversationMemory`←实现 `ConversationMemoryService`)；仅模块内部使用的服务可直接用实现类，不做多余抽象。

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

### 向量化与 RAG 数据规范

- 入库流程: 上传 -> `knowledge_document(status=0)` -> 异步(Tika 解析 -> TokenTextSplitter 512/100 分块 -> 元数据 doc_id/file_name/chunk_index/collection -> 向量化入库) -> 同步注册 `KeywordIndex`(BM25) -> status=2/3。
- 向量点元数据需含 `doc_id`/`file_name`/`chunk_index`(删除与溯源依据)；文档删除按 doc_id 过滤检索出点 id 后精确删除，并同步移除关键词索引。
- 检索链路: 意图路由(KB/GENERAL) -> 混合召回(语义+BM25) -> RRF -> 重排(`score`/`llm`/`none`) -> Top-K 注入。
- **相似度阈值勿随意上调**: `similarity-threshold` 默认 0.45——实测该 embedding 模型分数量级偏低
  (正确 chunk 可能只有 0.5~0.6), 阈值 0.6 会误杀正确答案(案例: "加班调休"0.565 被过滤导致答非所问)。
  调整前必须用 `/api/ai/rag/search` 以低阈值观察真实分数分布。
- **KeywordIndex 重启即空**(进程内 BM25): 语义阈值下调可缓解, 但"词面强匹配"场景仍需 BM25 路;
  后续优化方向: 启动时对 status=2 文档自动重建关键词索引(仅重建索引, 不重新向量化)。
- 每次对话的意图路由/检索决策自动落库 `rag_decision_log`(见 `/api/system/rag-decisions`)。

### 对话流水线与 SSE 事件契约

- 流式接口 `/api/ai/chat/stream` 输出**类型化事件**(ServerSentEvent.event 字段)：
  `content`(正文增量)、`sources`(引用来源**文档名** JSON 数组, 去重保序)；结尾 `data:[DONE]`(无 event 头, 兼容契约)。
  不推送任何阶段提示/思考流事件(已按需求移除)。
- 意图路由关键词表(缺省含年假/报销/放假/节假日/中秋等)决定是否检索; 语料外问题返回"未找到"属正确行为。
- 前置阶段(改写/检索/装配)在 boundedElastic 执行, 各阶段耗时必须打 INFO 日志(改写 ms/前置 ms/总 ms)——
  端点延迟标定的数据来源, 禁止删除。
- qwen3 类模型思维链控制: `extraBody(enable_thinking)` 按调用注入——改写/摘要默认关闭
  (机械任务, 实测 10~29s→亚秒~数秒), 主对话默认开启(`app.chat.disable-thinking=false`)可配置。

### 多轮查询改写

- 改写基于 Spring AI 内置 `CompressionQueryTransformer`(spring-ai-rag 模块, 包名
  `org.springframework.ai.rag.preretrieval.query.transformation`), 把"最近 6 条历史 + 当前追问"
  压缩为独立检索查询; 不再使用自维护的 query-rewrite.st 模板。
- **指代词启发式**(`reference-hint-required=true`): 问题不含 `reference-hints` 线索词(那/它/他/这个…)时
  跳过改写直接检索(完整问题零等待); 含线索词才调 LLM 改写。
- 改写调用注入 `enable_thinking=false`(disable-thinking=true, 实测 10~29s→0.6s)。
- 改写同步执行于提问路径, 受多重保护: 超时(`query-rewrite.timeout-ms`, 默认 30000)、
  **熔断**(连续失败≥2 次进入 60s 冷却, 冷却期内零等待回退原问题, 成功即复位)、结果等同原问题视为未改写。
- 每次调用记录实际耗时(成功 INFO / 失败 WARN), 禁止删除——这是端点延迟标定的数据来源。

### Spring AI 内置组件采用策略(2026-09 评审结论)

- **已采纳内置**:`CompressionQueryTransformer`(查询改写)·`spring.ai.retry.*`(模型调用重试,
  max-attempts=2 只补瞬态失败, 与业务超时/熔断互补)·`SimpleLoggerAdvisor`(DEBUG 级打印 prompt/响应, 调试用)·
  TikaDocumentReader+TokenTextSplitter(文档 ETL)·ChatClient/@Tool(基础装配)。
- **保持自研(无内置或自研更强)**:滚动摘要(无内置组件)·Token 预算四段装配(ContextAssembler,
  augmenter 模板做不了程序化截断)·混合检索 RRF+融合重排(2.0 的 postretrieval 仅有接口无实现)·
  意图路由(无内置)。
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
- 摘要未就绪时装配走"窗口历史 + Token 预算"兜底, 不丢上下文; 摘要失败保留现状, 下轮写回后再触发。
- 同会话的 append/摘要/裁剪共用 per-session 锁与防重入标记, 防止并发覆盖丢消息。

### 用户与鉴权

- 注册/登录: `/api/auth/register`、`/api/auth/login`，密码 PBKDF2 加盐哈希(`PasswordHasher`)。
- 角色: `sys_user.role`(ADMIN/USER, 默认 USER), 登录时写入 JWT `role` claim; `UserContext.CurrentUser.isAdmin()` 判定。
- 授权: "管理员或本人"类查询用 `@RequireSelfOrAdmin(userIdParam = "...")` 注解 + `SelfOrAdminAspect` 切面统一强制改写 userId 参数, 数据隔离由 Service 层 userId 过滤完成; 越权/无角色返回 `AUTH_FAILED`(5002, HTTP 403)。
- 日志归属: 四张日志表均含 `user_id`(tool_call_log 经 Spring AI `toolContext` 透传回填), 查询接口一律按"管理员或本人"过滤。
- JWT: `JwtTokenProvider` 签发 HS256；请求带 `Authorization: Bearer <token>`。
- 拦截器 `AuthInterceptor` 保护 `/api/**`，通过 `UserContext` 暴露当前用户；`X-User-Id` 模拟登录默认关闭, 仅 dev profile 开启, 严禁生产开启。

### 统一返回值格式规范

- 所有 Controller 返回 `Result<T>` 或 `Result<PageResult<T>>`，禁止直接返回 Entity。
- `Result` 结构: `{code, message, data, timestamp}`；`PageResult`: `{records,total,page,size,totalPages}`。
- 错误码集中定义在 `ErrorCode`(1001~5002, 用户相关 6001~6005)，业务异常抛 `BusinessException(ErrorCode, message)`，由 `GlobalExceptionHandler` 统一兜底，禁止向客户端泄漏堆栈。

### Spring AI & RAG 提示词

- System 提示词外部化到 `classpath:/prompts/*.st`(base-system/rag-context/general-system)。
- 注入上下文前必须经重排或相似度阈值过滤。

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

---

## 后续优化路线

- 性能/安全/成本优化的待评估项（P1 性能成本 / P2 安全加固 / P3 架构级）统一登记在
  `docs/optimization-roadmap.md`——含每项的现状实证、方案、验收标准与触发条件；
  实施前先读该文档, 完成后在"已完成记录"补行。
- P0 已落地: JWT 密钥启动强校验(`SecurityConfigValidator`, prod 拒绝默认/短密钥)、
  登录失败限流(`LoginAttemptLimiter`, 用户名+IP 双维度 5 次锁 5 分钟, 错误码 6006/429)、
  Hikari 连接池显式配置(max 25)。登录接口签名已变: `AuthService.login(LoginContext)`。

## 测试策略

- 单元测试使用 Mockito Mock `ChatModel`/`EmbeddingModel`，禁止调用真实 API。
- 集成/冒烟优先真实环境脚本(`docs/demo.sh`)，断言含返回文本与落库记录。
- 数据库相关用例依赖 MySQL + sql init 脚本建表(需本地/容器 MySQL 可用)。

## 常用命令

- **开发:** `mvn spring-boot:run`(安全基线) / `mvn spring-boot:run -Dspring-boot.run.profiles=dev`(开启模拟登录头)
- **测试:** `mvn test`
- **清理:** `mvn clean`
- **基础设施:** `docker compose up -d` (Qdrant/MySQL)
- **建表:** 由启动期 `spring.sql.init` 自动执行(db/ 下脚本)。
- **测试后必须停后端:** 每次运行/验证结束立即停止后端进程并确认 9090 端口已释放——
  Git Bash 的 `kill` 常杀不死 Windows 进程, 需用
  `netstat -ano | grep :9090` 找 PID 后 `taskkill //F //PID <pid>`; 残留实例会占住端口,
  导致下次启动失败或验证跑到旧代码。
