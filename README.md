# 智能问答 Agent（Spring AI + RAG）

基于 **Spring Boot 4 / Spring AI 2.0.1** 的智能问答系统快速迭代版。按《智能问答系统-功能需求开发文档》覆盖**知识库管理、智能对话(RAG+流式)、Agent 工具、会话管理、系统日志、统一响应与降级**等模块，聚焦「能跑通全链路」，每模块均已落地可调用接口。

## 1. 技术栈（实际使用版本）

| 组件 | 版本/说明 |
|---|---|
| JDK / 框架 | Java 21 · Spring Boot 4.1.1（webmvc starter 命名） |
| AI 框架 | Spring AI 2.0.1（`spring-ai-starter-model-openai`，OpenAI 兼容协议） |
| 大模型 | 阿里云百炼 DashScope（qwen3.7-flash / qwen3.7-text-embedding），兼容本地 Ollama 等 OpenAI 兼容网关 |
| 向量库 | Qdrant（唯一向量库，首次启动自动建集合，维度随 embedding 模型） |
| 业务库 | MySQL 8（唯一关系库，表由启动期 `spring.sql.init` 幂等建表） |
| 持久化 | MyBatis-Plus 3.5.17（BaseMapper + LambdaQueryWrapper；表结构由启动期 `spring.sql.init` 脚本维护） |
| 对话记忆 | 自研 `DbChatMemoryRepository`（框架约定表 `SPRING_AI_CHAT_MEMORY`，content 列 JSON 序列化）+ 自管历史装配；官方 `spring-ai-starter-model-chat-memory-repository-jdbc` 在 2.0.1 BOM 中已存在，经对比暂保留自研（官方表多 `sequence_id` 列，迁移评估见 agents.md） |
| 文档解析 | Tika（PDF/DOCX/TXT/MD） + `TokenTextSplitter`(512/100) |
| 其它 | AOP(工具日志) · Lombok · 虚拟线程 |

> 架构采用**模块优先**组织：每个业务模块自持 `controller/service/entity/mapper/dto` 子包，模块根包只放对外契约；全局切面统一在 `aspect` 包，业务无关工具在 `common`。完整规范与强制规则见 `AGENTS.md`「项目架构规范」，并由 `LayeredArchitectureTest`(ArchUnit) 自动守护。

## 2. 快速开始（依赖 MySQL + Qdrant + Redis）

前置：JDK 21、Maven 3.9+、MySQL 8、Qdrant（`docker compose up -d` 一键起）、Redis（**compose 未纳管，需自行启动**，默认 `localhost:6379`，可用 `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` 覆盖；用于登录限流/会话缓存/语义缓存/意图缓存/令牌黑名单，全部为"异常即降级"设计，不起也能跑通登录与对话，只是失去缓存与限流）。对话与入库需要大模型 API Key（否则应用可启动，相关能力友好降级）。

**仓库内不含任何口令/密钥默认值**（历史上放过 `123456` 与一个默认 JWT 密钥，均已移除）。本地启动前先准备环境变量：

```bash
cp .env.example .env      # 供 docker compose 使用: 填 MYSQL_ROOT_PASSWORD 等(.env 已被 gitignore)
docker compose up -d      # 端口只绑 127.0.0.1(3306/6333/6334), 不暴露到局域网

# 让应用拿到数据库口令(二选一, 见"数据源与环境变量"一节)
#   ① IDEA/本地推荐: 模块根目录建 application-local.yaml(已 gitignore), 内容一行即可:
#        DB_PASSWORD: "你的口令"
#   ② 命令行: 用环境变量
# Windows PowerShell
$env:DB_PASSWORD="你的本地口令"
$env:DASHSCOPE_API_KEY="sk-xxxx"
# JWT_SECRET 可留空(本地自动生成一次性密钥); 生产留空会拒绝启动
mvn spring-boot:run       # 或打包运行
mvn -DskipTests package && java -jar target/ai-agent-0.0.1-SNAPSHOT.jar
```

> 跑 `mvn test` 同理需要能连上 MySQL——建了 `application-local.yaml` 就直接 `mvn test`，不必再设环境变量。若你沿用了仓库里曾出现过的 `123456`，**请先改掉本机 MySQL 口令**——那个值已随历史提交公开。

启动后：
- 应用端口 **9090**；Qdrant 控制台 http://localhost:6333/dashboard
- 示例业务数据自动初始化（员工：张三/李四/王五；订单：SO20260101xxx）

> 切换大模型只需环境变量：`AI_BASE_URL`、`AI_CHAT_MODEL`、`AI_EMBEDDING_MODEL`、`DASHSCOPE_API_KEY`。
> 本地 Ollama：`AI_BASE_URL=http://localhost:11434/v1`、`AI_CHAT_MODEL=qwen2.5:7b`、`AI_EMBEDDING_MODEL=nomic-embed-text`（需 Ollama 已拉取相应模型）。

### 数据源与环境变量

应用默认且仅使用 **MySQL（关系库）+ Qdrant（向量库）**，配置集中在 `application.yaml`，无需指定 profile。可用环境变量覆盖：

| 用途 | 环境变量 | 缺省 |
|---|---|---|
| MySQL 连接 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:mysql://127.0.0.1:3306/ai_agent_db...` / `root` / **无默认值**（见下方"本地口令怎么传"） |
| Qdrant 连接 | `QDRANT_HOST` / `QDRANT_PORT` / `QDRANT_API_KEY` | `127.0.0.1` / `6334`（gRPC） / 空 |
| 大模型 | `DASHSCOPE_API_KEY` / `AI_BASE_URL` / `AI_CHAT_MODEL` / `AI_EMBEDDING_MODEL` | 见 `application.yaml` |

**本地口令怎么传**（`DB_PASSWORD` 在仓库里没有默认值，不传就连不上库——这是防泄漏的刻意设计）：

| 场景 | 做法 |
|---|---|
| **IDEA 里点启动** | 在模块根目录建 `application-local.yaml`（已 gitignore）写一行 `DB_PASSWORD: "你的口令"`，由 `spring.config.import: optional:file:./application-local.yaml` 自动加载，零配置即可启动 |
| 命令行 | `DB_PASSWORD=xxx mvn spring-boot:run`（PowerShell：`$env:DB_PASSWORD="xxx"`） |
| 生产 | 部署平台注入环境变量 |

> ⚠ IDEA 的运行配置**不继承**你在终端里 `export` 的变量，所以只走命令行那条路会让 IDE 启动一直失败——请用上面的 `application-local.yaml`。
> `.env` 只被 `docker compose` 读取，Spring Boot 不认它。

> 首次启动 Qdrant 自动建集合（需 Embedding 模型可用）；MySQL 表由启动期 `spring.sql.init` 幂等建表（脚本全部 `IF NOT EXISTS`，`continue-on-error` 已关闭）。
>
> **运行 profile**：默认（无 profile）= 安全基线（模拟登录头关闭、演示数据播种开启）；`dev` profile 额外开启 `X-User-Id` 模拟登录；`prod` profile 关闭演示数据播种。`/actuator/health` 可用于探针。

## 3. 功能模块与接口

统一前缀 `/api`，统一响应 `{code,message,data,timestamp}`，分页 `{records,total,page,size,totalPages}`。除流式接口外均返回 `Result<T>`。

### 3.0 用户与鉴权（新增）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/auth/register` | 注册（username/password/nickname），成功即返回登录态 |
| `POST /api/auth/login` | 登录，返回 `{token, user}`（JWT） |
| `GET /api/auth/me` | 当前登录用户信息 |

- 登录后业务接口统一携带请求头：`Authorization: Bearer <token>`；未登录/凭证失效返回 code=6005(HTTP 401)。
- 所有 `/api/**`（除 register/login）由 `AuthInterceptor` 鉴权，会话/知识库上传的“用户”取自登录态（不再手工传 userId）。
- 开发便捷开关默认**关闭**：需要用 `X-User-Id` 请求头模拟登录时，以 dev profile 启动（`--spring.profiles.active=dev`）；默认管理员 admin/admin123 首次启动自动播种（可用 `app.demo.seed-enabled=false` 关闭，prod profile 默认关闭）。
- **角色与授权**：`sys_user.role`（ADMIN/USER，登录时写入 JWT `role` claim，重新登录后刷新）。授权规则"管理员或本人"由 `@RequireSelfOrAdmin` 注解 + `SelfOrAdminAspect` 切面统一实施——管理员可查全量；普通用户在系统日志接口传入的 `userId` 会被**强制改写为本人**（越权传他人 ID 只能看到自己的数据），非本人数据返回 HTTP 403（code=5002）。

### 3.1 知识库管理（需求第 2 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/knowledge/upload`（multipart: file） | 上传文档（PDF/DOCX/TXT/MD ≤50MB，**空文件拒绝 1005**），异步入库，上传人=登录用户 |
| `POST /api/knowledge/upload/batch`（multipart: files 多字段） | **批量上传**：逐文件独立校验与入库，单个失败不影响其它，返回每文件成功/失败原因 |
| `GET /api/knowledge/documents` | 分页列表（fileName/status/startTime/endTime 过滤） |
| `DELETE /api/knowledge/documents/{id}` | 删除：按 doc_id 清理向量 → 删除记录 → 删除本地文件 |
| `POST /api/knowledge/documents/{id}/reprocess` | 重新入库（失败重试） |

入库链路：上传 → `knowledge_document(status=0)` → `DocumentIngestionService`(@Async ingestionExecutor) → Tika 解析 → TokenTextSplitter 分块(512/100) → 附加元数据(doc_id/file_name/chunk_index…) → 向量化写入 VectorStore → status=2/3（分块数与真实 chunk 一致，记录于 chunk_count）。

### 3.2 智能对话（需求第 3 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/ai/chat` | 同步问答，返回 `{content}`（引用来源不再返回前端，只落库 `chat_log.sources`） |
| `POST /api/ai/chat/stream` | SSE 类型化事件流：`event:content`(正文增量) → `data:[DONE]`；不推送阶段提示/思考流/来源事件（引用来源只落库，审计走系统日志接口） |
| `POST /api/ai/rag/search` | RAG 检索调试（topK/阈值即时调参看命中） |

按会话类型路由：`RAG`=仅检索注入；`AGENT`=仅工具；`HYBRID`=两者兼备（默认）。
流程：校验会话 → **多轮查询改写**（`QueryRewriter` 指代消解，失败回退原文）→ **语义缓存查询**（`SemanticAnswerCache`：仅"未改写的独立问题 + KB 路由"参与，键=知识库版本号+对话模型标识+问题 SHA-256（模型名取自 `ChatClientProvider.modelLabel()`，与 `chat_log.model_name` 同源，切换模型后旧模型的回答不再命中）；正缓存命中则跳过检索与装配直接返回；负缓存命中则直接返回固定"未找到"文案，均流式只发 1 个 `content` 事件；因缓存条目跨用户共享，**本轮发生过工具调用的回答不写缓存**）→ **意图路由**（`app.rag.auto-route=true` 时：常识/闲聊问题自动跳过检索、以 `general-system.st` 自由作答；命中内部关键词（`app.rag.internal-keywords` 可覆盖缺省词表）才执行检索）→ **多路召回与重排**（语义向量检索 + 关键词 BM25(`KeywordIndex`) 两路召回 → RRF 融合 → 按 `app.rag.rerank-mode` 重排：`score` 分数融合 / `llm` 大模型重排(失败回退 score) / `none` 仅 RRF）→ **上下文装配**（`ContextAssembler` 统一 Token 预算切分 system/历史/RAG/user，历史含滚动摘要）→ 模型生成（可携带 `BusinessTools`）→ 写回会话记忆 → 写缓存。**意图路由/检索决策与对话/上下文日志通过事件异步落库**（`ChatAuditListener`，可用 `/api/system/rag-decisions`、`/api/system/context-logs` 审计）。`app.rag.auto-route=false` / `hybrid-enabled=false` 可分别关闭路由与混合检索。

### 3.3 Agent 工具（需求第 4 章）
- `BusinessTools`：`queryEmployee(姓名)`、`queryOrder(订单号)`、`getCurrentTime()`（`@Tool`/`@ToolParam` 描述触发条件与参数）
- `ToolCallLogAspect`（AOP）自动记录每次工具调用的入参/出参/耗时/状态到 `tool_call_log`；会话与用户经 Spring AI `toolContext` 透传进切面（session_id/user_id 已完整落库），同一 `toolContext` 还携带本轮工具调用计数（`AtomicInteger`），供收尾阶段判断"该轮答案含实时/个性化数据"并跳过语义缓存写入

### 3.4 会话管理（需求第 5 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/sessions` | 创建会话（sessionType: RAG/AGENT/HYBRID，默认 HYBRID） |
| `GET /api/sessions` | 我的会话（分页，自动按登录用户隔离） |
| `GET /api/sessions/{id}` | 单个会话详情 |
| `PUT /api/sessions/{id}/title` | 手动改名（人工标题优先，不会被自动标题覆盖） |
| `PUT /api/sessions/{id}/archive` | 归档（status=0） |
| `DELETE /api/sessions/{id}` | 删除 = 软删 + 清理对话记忆 |

> **会话标题自动生成**：新会话首轮提问时，后端先同步把问题截断成标题占位（`app.session-title.fallback-chars`），
> 再用模型概括精修（异步，与本轮回答并行；`model-enabled: false` 可只保留截断版）。模型不可用一律停留在截断标题，不影响对话。

### 3.5 系统管理（需求第 6 章）
> **授权**：四个日志查询接口均带 `@RequireSelfOrAdmin`——管理员可按任意 `userId`/`sessionId` 过滤全量；普通用户强制只查本人（不传 `userId` 即本人全量，传他人 `userId` 被改写为本人）。语义缓存清空为管理动作，带 `@RequireAdmin`。

| 方法/路径 | 说明 |
|---|---|
| `GET /api/system/chat-logs` | 对话日志分页（sessionId/userId/时间范围过滤） |
| `GET /api/system/tool-call-logs` | 工具调用日志分页（sessionId/userId/toolName/status/时间过滤） |
| `GET /api/system/tool-call-logs` | 工具调用日志分页（toolName/status/时间过滤） |
| `GET /api/system/rag-decisions` | RAG 意图路由决策日志分页（sessionId/userId/ragMode/时间过滤：KB/GENERAL、是否检索、多路命中数、Top-K/阈值/重排模式、耗时） |
| `GET /api/system/context-logs` | 上下文装配日志分页（各段 token 占用/是否截断/改写结果/耗时） |
| `DELETE /api/system/semantic-cache` | 清空语义缓存（管理员），返回失效后的知识库版本号；文档变更已自动失效，切换对话模型也已按键隔离（无需手工清空），此接口用于“回答质量异常”的人工强制失效 |

### 3.6 异常与降级（需求第 8 章）
- `GlobalExceptionHandler` + `ErrorCode`（1001~5004）统一错误，业务错误返回语义化 HTTP 状态（参数 400 / 未找到 404 / 冲突 409 / 认证 401 / 无权 403 / 上游模型失败 502 / 不可用 503）；模型调用失败不向前端透传内部异常细节；
- 降级策略：模型不可用 → `AI_NOT_CONFIGURED` 友好提示；向量库不可用 → RAG 自动降级为不注入上下文继续对话；入库失败 → 仅标记 `status=3`，不影响在线对话；删除失败 → 记录日志继续。
- **Redis 不可用 → 一律降级，绝不影响可用性**：登录限流读写两侧捕获异常按"未锁定"放行（否则基础设施故障会变成登录接口 500/5001）、会话缓存回退 MySQL、语义/意图缓存按未命中、令牌黑名单按 `app.auth.blacklist-fail-open`（**生产基线为 false=拒绝**，由启动校验强制）。降级日志统一 WARN 且按 60 秒节流（`WarnThrottle`），启动时 `RedisReadinessProbe` 输出一条"当前处于降级态的能力清单"。口径与理由见 `AGENTS.md`「Redis 使用与降级约定」。

## 4. 快速验证示例（curl）

```bash
BASE=http://localhost:9090

# 0) 登录获取 Token(默认管理员 admin/admin123, 也可先 /api/auth/register 注册)
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r .data.token)
AUTH="Authorization: Bearer $TOKEN"

# 1) 创建会话(默认 HYBRID)
curl -s -X POST $BASE/api/sessions -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"title":"员工制度咨询"}'
# => data.sessionId 记作 $SID

# 2) 上传示例知识文档(异步入库, 稍候查状态=2; 上传人=登录用户)
curl -s -X POST $BASE/api/knowledge/upload -H "$AUTH" -F "file=@docs/sample/员工手册示例.md"
curl -s "$BASE/api/knowledge/documents?status=2" -H "$AUTH"

# 3) RAG 检索调试(看命中分块与分数)
curl -s -X POST $BASE/api/ai/rag/search -H "Content-Type: application/json" -H "$AUTH" \
  -d '{"question":"入职满两年的员工有多少天年假？","topK":3,"similarityThreshold":0.3}'

# 4) 同步问答(知识库问题 / 工具问题 / 多轮记忆)
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"入职满两年能休几天年假？\"}"
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"张三在哪个部门？\"}"
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"那他的联系方式呢？\"}"   # 验证多轮记忆

# 5) 流式问答
curl -s -N -X POST $BASE/api/ai/chat/stream -H "Content-Type: application/json" -H "$AUTH" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"报销住宿标准是多少？\"}"

# 6) 系统日志回看(应有本轮来源 JSON / 工具调用日志)
curl -s "$BASE/api/system/chat-logs?sessionId=$SID" -H "$AUTH"
curl -s "$BASE/api/system/tool-call-logs" -H "$AUTH"
```

更完整的可执行示例见 [docs/demo.sh](docs/demo.sh)。

## 5. 目录结构

```
com.ai
├── common      【全局公共层】统一响应/错误码/异常/工具类/Token 计量(jtokkit 精确计数 JtokTokenCounter, HeuristicTokenCounter 为备用)
├── aspect      【全局切面层】SelfOrAdminAspect(授权) · ToolCallLogAspect(工具日志)
├── config      【全局配置层】AppProperties · 异步池 · ChatClient 装配 · MVC/跨域 · MyBatis-Plus 装配 · 种子数据
├── prompt      【提示词装配】PromptService(classpath:/prompts/*.st)
├── rag         【RAG 能力模块】根=契约(RagRetriever/IntentRouter/RagMode/RetrievalOutcome/SemanticCacheAdmin) · service/=混合检索与关键词索引实现
├── context     【上下文管线模块】根=契约(ConversationMemory/HistoryContext/AssembledPrompt/ContextComposition)
│   ├── entity|mapper  ConversationSummary
│   └── service        ContextAssembler · ConversationMemoryService · ConversationSummarizer · QueryRewriter
├── chat        【对话模块】controller/ChatController · service/ChatService(门面)+ChatPreparationService(前置)+ChatCompletionService(收尾)+ChatSourceDisplay(来源口径)+ChatConcurrencyGuard(并发) · event/审计事件 · dto
├── knowledge   【知识库模块】controller/service(上传入库/文档管理/文件存储)/entity/mapper/dto
├── user        【用户鉴权模块】controller/service/entity(SysUser)/mapper/dto
│   └── security       JwtTokenProvider · UserContext · AuthInterceptor · PasswordHasher · RequireSelfOrAdmin
├── session     【会话模块】controller/service/entity(ChatSession)/mapper/dto
├── system      【系统审计模块】controller/SystemController · service/四类日志服务+ChatAuditListener · entity/mapper/dto(四张日志表)
├── agent       【Agent 工具模块】BusinessTools(@Tool) + entity/mapper/dto(员工/订单演示数据)
└── memory      【记忆存储模块】DbChatMemoryRepository + entity/mapper(SPRING_AI_CHAT_MEMORY)
resources/prompts        *.st 提示词模板
resources/application.yaml   MySQL + Qdrant 默认配置(无 profile)
db/create_table.sql          建表脚本(SPRING_AI_CHAT_MEMORY / knowledge_document / chat_session / tool_call_log / chat_log)
docs/sample/员工手册示例.md   演示知识文档
docs/arch/                   架构图与流程图(archify 生成: *.json 源 + *.html 交互式成品 + visual-check 验收截图)
docker-compose.yml          Qdrant+MySQL
```

> 系统架构与工作流程图见 [docs/arch/](docs/arch/)：`architecture.html`(系统架构) · `workflow.html`(问答主流程) ·
> `workflow-wrapup.html`(生成分支与收尾)。HTML 自带明暗主题切换、平移缩放、搜索定位与关系追踪；
> 三张图均已通过 archify showcase 校验(9/9 checks) 与桌面视口浏览器验收(1440×900 起无溢出、字号 ≥7.1px)。

> 分层约束由 `LayeredArchitectureTest`(ArchUnit) 固化：业务包禁止循环依赖、common/entity 不反向依赖业务包、Controller 禁止直连 Mapper。跨模块调用的 Service 一律以接口暴露（`RagRetriever` / `IntentRouter` / `ConversationMemory` / `SemanticCacheAdmin`），调用方依赖接口而非实现。

> 全量流程与逐方法说明（Markdown/Mermaid，可随代码一起 review）见 [docs/flow-map.md](docs/flow-map.md)（20 张图：全景、启动装配、鉴权横切、认证用例、知识库上传与状态机、对话前置/同步/流式、混合检索、上下文装配、缓存写入闸门、工具与切面、审计落库、会话与记忆、前端映射、降级总表、存储键空间、线程模型、e2e 对照、偏差清单）与 [docs/method-map.md](docs/method-map.md)（133 个类逐方法作用与调用方）。

> 对话管线：`ChatService` 只做门面（会话校验/并发名额/请求链构建/同步与 SSE 输出编排）；前置（改写→语义缓存查询→路由检索→决策审计→装配）与收尾（记忆写回→摘要→完成审计→缓存写入→来源落库）各一份实现，由同步与流式共用，避免两条管线逻辑漂移。

## 6. 已知简化与后续路线（非阻塞项）

- **前端**：已提供独立仓库 `../ai-agent-web`（Vue 3.5 + Vite 7 + Pinia + axios，自写样式无组件库；5 个视图：登录 / 对话 / 知识库 / 检索调试 / 系统日志），调用面与 §3 端点一一对应（见 `docs/flow-map.md` §17）。后端仍是主交付物，前端未纳入本仓库的构建与测试。
- **存量数据库升级**：本版本新增 `sys_user.role` 与 `tool_call_log`/`rag_decision_log`/`context_log` 的 `user_id` 列。已有库需手动执行一次 `db/upgrade/2026-09-authorization.sql`（MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`，无法随启动脚本幂等执行）；新库由建表脚本直接生效。
- **表/列注释补齐**：脚本已为 11 张表补齐表注释、66 个缺注释列补齐列注释。已有库执行一次 `db/upgrade/2026-09-column-comments.sql`（该脚本由 existing 库的真实结构生成，仅追加 `COMMENT`，不改类型/可空/默认值；重复执行无害）。**已知历史差异（脚本刻意未动）**：`employee`/`orders`/`sys_user`/`rag_decision_log` 的 `created_at`/`updated_at` 仍是早期 Hibernate 建表遗留的 `datetime(6) NULL`（无默认值），而建表脚本声明为 `DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`；如需对齐需另行评估（涉及 NOT NULL 变更，须先确认存量无 NULL 值）。
- **chat_log.total_tokens**：已采集（从模型响应 `getMetadata().getUsage()` 取总量，配合 `TokenCounter` 的上下文预算审计 `context_log`）；模型不返回 usage 时该列为空。
- **删除文档**：物理删除记录 + 按 `doc_id` 过滤检索出向量点后精确清理（向量库不可用时记录日志并继续），文件删除尽力而为。
- **表范围**：核心 5 张表见 `db/create_table.sql`；扩展表 `employee` / `orders` / `sys_user` / `rag_decision_log` / `conversation_summary` / `context_log` 见 `db/schema-mysql-extra.sql`，由启动期 `spring.sql.init` 幂等执行建表（仅 MySQL）。
- **关键词召回索引(KeywordIndex)为进程内存实现**：与外部 Qdrant 向量库相互独立，入库/删除/重处理自动同步增删；应用重启后由 `KeywordIndexRebuilder` 从 Qdrant payload 自动重建（不重新向量化，秒级完成），可用 `app.rag.auto-rebuild-index=false` 关闭。
- **重排模式**：默认 `score`(纯计算)；`llm` 模式每轮额外调用一次模型对候选排序(失败自动回退 score)，请注意额外成本与延迟。
- **Qdrant 维度/量化**：由 Spring AI 自动管理集合；海量数据建议按需求第 9 章启用 HNSW 调参与 Scalar Quantization。
- **测试用例**：单元测试 167 例（Mockito，含 ArchUnit 架构守护 7 条规则）用 `mvn test` 运行；端到端脚本 `docs/seed/e2e_test.py` 覆盖 52 项断言（注册/登录→会话→多轮对话含工具与改写→SSE→日志授权→语义缓存命中与失效→知识库增删→异常路径），需应用已启动且 MySQL/Qdrant/Redis 可用。脚本段 1 会清空语义缓存建立冷基线，可重复执行。

## 7. 常见问题

- **401/403/404**：检查 `DASHSCOPE_API_KEY`、`AI_BASE_URL`、模型名（qwen-plus 是否开通）；百炼控制台核实。
- **上传后状态一直为 1 或变 3**：查看 `knowledge_document.error_message`；多为 Embedding 未配置/额度不足/文件解析失败。列表接口已返回 errorMessage。
- **问答答“未找到相关信息”**：知识库无命中（阈值过高或未上传文档）；用 `/api/ai/rag/search` 调试 topK 与阈值。
- **切换向量库维度不一致**：同一 Embedding 模型产出的维度必须一致；换模型请清空旧集合/旧库再入库。
- **登录与对话都正常，但缓存全不命中/限流好像失效**：看启动日志的 `Redis 自检失败(不可用)` 一行，它会列出当前处于降级态的能力清单；运行期同类降级为 WARN（60 秒一条）。Redis 未启动时应用按设计继续服务，这不是故障。
