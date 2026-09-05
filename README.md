# 智能问答 Agent（Spring AI + RAG）

基于 **Spring Boot 4 / Spring AI 2.0.1** 的智能问答系统快速迭代版。按《智能问答系统-功能需求开发文档》覆盖**知识库管理、智能对话(RAG+流式)、Agent 工具、会话管理、系统日志、统一响应与降级**等模块，聚焦「能跑通全链路」，每模块均已落地可调用接口。

## 1. 技术栈（实际使用版本）

| 组件 | 版本/说明 |
|---|---|
| JDK / 框架 | Java 21 · Spring Boot 4.1.1（webmvc starter 命名） |
| AI 框架 | Spring AI 2.0.1（`spring-ai-starter-model-openai`，OpenAI 兼容协议） |
| 大模型 | 阿里云百炼 DashScope（qwen-plus / text-embedding-v3），兼容本地 Ollama 等 OpenAI 兼容网关 |
| 向量库 | 默认进程内 `SimpleVectorStore`（零依赖）；`qdrant` profile 切换外部 Qdrant |
| 业务库 | 默认 H2 内存（零依赖）；`mysql` profile 切换 MySQL 8 |
| 持久化 | Spring Data JPA（Hibernate ddl-auto=update 自动建表） |
| 对话记忆 | 自研 `DbChatMemoryRepository`（框架约定表 `SPRING_AI_CHAT_MEMORY`，content 列 JSON 序列化）+ `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`（Spring AI 2.x 已移除官方 JDBC starter，故自实现） |
| 文档解析 | Tika（PDF/DOCX/TXT/MD） + `TokenTextSplitter`(512/100) |
| 其它 | AOP(工具日志) · Lombok · 虚拟线程 |

> 架构分层遵循 `AGENTS.md`：Controller(薄) → Service(业务/事务) → Repository(JPA)；统一 `Result<T>` / `PageResult<T>`；实体不直接返回前端。

## 2. 快速开始（默认零外部依赖）

前置：JDK 21、Maven 3.9+。对话与入库需要大模型 API Key（否则应用可启动，相关能力友好降级）。

```bash
# Windows PowerShell
$env:DASHSCOPE_API_KEY="sk-xxxx"

# 方式一：直接启动（默认 H2 + 内存向量库）
mvn spring-boot:run
# 或打包运行
mvn -DskipTests package
java -jar target/ai-0.0.1-SNAPSHOT.jar
```

启动后：
- 应用端口 **9090**；H2 控制台 http://localhost:9090/h2-console（`jdbc:h2:mem:ai_agent`，sa/空）
- 示例业务数据自动初始化（员工：张三/李四/王五；订单：SO20260101xxx）

> 切换大模型只需环境变量：`AI_BASE_URL`、`AI_CHAT_MODEL`、`AI_EMBEDDING_MODEL`、`DASHSCOPE_API_KEY`。
> 本地 Ollama：`AI_BASE_URL=http://localhost:11434/v1`、`AI_CHAT_MODEL=qwen2.5:7b`、`AI_EMBEDDING_MODEL=nomic-embed-text`（需 Ollama 已拉取相应模型）。

### Profile 一览

| 场景 | 启动命令 |
|---|---|
| 本地快速体验（默认） | `mvn spring-boot:run` |
| 使用 MySQL | `mvn spring-boot:run -Dspring-boot.run.profiles=mysql`（环境变量 DB_URL/DB_USERNAME/DB_PASSWORD） |
| 使用外部 Qdrant | `mvn spring-boot:run -Dspring-boot.run.profiles=qdrant` |
| MySQL + Qdrant 生产形态 | `docker compose up -d` 后 `mvn spring-boot:run -Dspring-boot.run.profiles=mysql,qdrant` |

> Qdrant profile 会放开 Qdrant 自动装配（`application-qdrant.yaml`），首次启动自动建集合（需 Embedding 模型可用）。

## 3. 功能模块与接口

统一前缀 `/api`，统一响应 `{code,message,data,timestamp}`，分页 `{records,total,page,size,totalPages}`。除流式接口外均返回 `Result<T>`。会话鉴权 MVP 用请求头 `X-User-Id`（默认 1）。

### 3.1 知识库管理（需求第 2 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/knowledge/upload`（multipart: file,userId） | 上传文档（PDF/DOCX/TXT/MD ≤50MB），异步入库 |
| `GET /api/knowledge/documents` | 分页列表（fileName/status/startTime/endTime 过滤） |
| `DELETE /api/knowledge/documents/{id}` | 删除：按 doc_id 清理向量 → 删除记录 → 删除本地文件 |
| `POST /api/knowledge/documents/{id}/reprocess` | 重新入库（失败重试） |

入库链路：上传 → `knowledge_document(status=0)` → `DocumentIngestionService`(@Async ingestionExecutor) → Tika 解析 → TokenTextSplitter 分块(512/100) → 附加元数据(doc_id/file_name/chunk_index…) → 向量化写入 VectorStore → status=2/3（分块数与真实 chunk 一致，记录于 chunk_count）。

### 3.2 智能对话（需求第 3 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/ai/chat` | 同步问答，返回 `{content, sources}` |
| `POST /api/ai/chat/stream` | SSE 流式（`data:{...}` … `data:[DONE]`） |
| `POST /api/ai/rag/search` | RAG 检索调试（topK/阈值即时调参看命中） |

按会话类型路由：`RAG`=仅检索注入；`AGENT`=仅工具；`HYBRID`=两者兼备（默认）。
流程：校验会话 → (检索 `similaritySearch` topK=5、相似度阈值 0.6 可配) → 组装 system（`prompts/base-system.st` / `rag-context.st` 外部模板）→ `MessageChatMemoryAdvisor` 注入历史（conversationId=sessionId）→ 模型生成（可携带 `BusinessTools`）→ 写 `chat_log`（含来源 JSON）。

### 3.3 Agent 工具（需求第 4 章）
- `BusinessTools`：`queryEmployee(姓名)`、`queryOrder(订单号)`、`getCurrentTime()`（`@Tool`/`@ToolParam` 描述触发条件与参数）
- `ToolCallLogAspect`（AOP）自动记录每次工具调用的入参/出参/耗时/状态到 `tool_call_log`

### 3.4 会话管理（需求第 5 章）
| 方法/路径 | 说明 |
|---|---|
| `POST /api/sessions` | 创建会话（sessionType: RAG/AGENT/HYBRID，默认 HYBRID） |
| `GET /api/sessions` | 我的会话（分页，X-User-Id） |
| `PUT /api/sessions/{id}/archive` | 归档（status=0） |
| `DELETE /api/sessions/{id}` | 删除 = 软删 + 清理对话记忆 |

### 3.5 系统管理（需求第 6 章）
| 方法/路径 | 说明 |
|---|---|
| `GET /api/system/chat-logs` | 对话日志分页（sessionId/userId/时间范围过滤） |
| `GET /api/system/tool-call-logs` | 工具调用日志分页（toolName/status/时间过滤） |

### 3.6 异常与降级（需求第 8 章）
- `GlobalExceptionHandler` + `ErrorCode`（1001~5003）统一错误；
- 降级策略：模型不可用 → `AI_NOT_CONFIGURED` 友好提示；向量库不可用 → RAG 自动降级为不注入上下文继续对话；入库失败 → 仅标记 `status=3`，不影响在线对话；删除失败 → 记录日志继续。

## 4. 快速验证示例（curl）

```bash
BASE=http://localhost:9090

# 1) 创建会话(默认 HYBRID)
curl -s -X POST $BASE/api/sessions -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"title":"员工制度咨询"}'
# => data.sessionId 记作 $SID

# 2) 上传示例知识文档(异步入库, 稍候查状态=2)
curl -s -X POST $BASE/api/knowledge/upload -F "file=@docs/sample/员工手册示例.md" -F "userId=1"
curl -s "$BASE/api/knowledge/documents?status=2"

# 3) RAG 检索调试(看命中分块与分数)
curl -s -X POST $BASE/api/ai/rag/search -H "Content-Type: application/json" \
  -d '{"question":"入职满两年的员工有多少天年假？","topK":3,"similarityThreshold":0.3}'

# 4) 同步问答(知识库问题 / 工具问题 / 多轮记忆)
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"入职满两年能休几天年假？\"}"
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"张三在哪个部门？\"}"
curl -s -X POST $BASE/api/ai/chat -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"那他的联系方式呢？\"}"   # 验证多轮记忆

# 5) 流式问答
curl -s -N -X POST $BASE/api/ai/chat/stream -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"message\":\"报销住宿标准是多少？\"}"

# 6) 系统日志回看(应有本轮来源 JSON / 工具调用日志)
curl -s "$BASE/api/system/chat-logs?sessionId=$SID"
curl -s "$BASE/api/system/tool-call-logs"
```

更完整的可执行示例见 [docs/demo.sh](docs/demo.sh)。

## 5. 目录结构

```
com.ai
├── common      Result / PageResult / ErrorCode / BusinessException / GlobalExceptionHandler / BaseTimeEntity
├── config      AppProperties / AsyncConfig / VectorStoreConfig / ChatConfig(记忆+ChatClient) / DemoDataInitializer
├── entity      KnowledgeDocument · ChatSession · ChatLog · ToolCallLog · Employee · SalesOrder · ChatMemoryRecord(SPRING_AI_CHAT_MEMORY)
├── repository  Spring Data JPA(含 JpaSpecificationExecutor 动态过滤)
├── memory      DbChatMemoryRepository(Spring AI ChatMemoryRepository 持久化实现)
├── dto         Java record 请求/响应 VO
├── service     FileStorage · RagRetrieval · DocumentIngestion · KnowledgeDocument · Chat · ChatSession · ChatLog · ToolCallLog · Prompt
├── agent       BusinessTools(@Tool) · ToolCallLogAspect(AOP)
└── controller  Knowledge / Chat / Session / System
resources/prompts        *.st 提示词模板
resources/application*.yaml  (默认/mysql/qdrant profile)
db/create_table.sql          建表脚本(SPRING_AI_CHAT_MEMORY / knowledge_document / chat_session / tool_call_log / chat_log)
docs/sample/员工手册示例.md   演示知识文档
docker-compose.yml          Qdrant+MySQL+Redis
```

## 6. 已知简化与后续路线（非阻塞项）

- **前端**：本期仅后端 REST/SSE；对接 Vue3+Element Plus 页面为下一迭代。
- **Redis 缓存/记忆**：需求第 1、9 章提及 Redis；MVP 使用 DB 记忆，Redis 接入已留 docker-compose 与配置位。
- **工具日志的 sessionId**：Spring AI 工具上下文暂未把 conversationId 透传进切面，`tool_call_log.session_id` 可为空（toolName/入参出参/状态/耗时已完整）。
- **chat_log.total_tokens**：本期未采集（字段保留）。
- **删除文档**：物理删除记录 + 按 `doc_id` 过滤检索出向量点后精确清理（向量库不可用时记录日志并继续），文件删除尽力而为。
- **表范围**：`db/create_table.sql` 覆盖框架记忆表与 4 张业务表；示例数据表 `employee` / `orders`（Agent 工具演示用）不在脚本内，由 JPA ddl-auto 自动创建。
- **Qdrant 维度/量化**：由 Spring AI 自动管理集合；海量数据建议按需求第 9 章启用 HNSW 调参与 Scalar Quantization。
- **测试用例**：需求第 10 章的功能用例可在本 README 第 4 节手工回归；自动化用例（Mockito/Testcontainers）作为后续迭代。

## 7. 常见问题

- **401/403/404**：检查 `DASHSCOPE_API_KEY`、`AI_BASE_URL`、模型名（qwen-plus 是否开通）；百炼控制台核实。
- **上传后状态一直为 1 或变 3**：查看 `knowledge_document.error_message`；多为 Embedding 未配置/额度不足/文件解析失败。列表接口已返回 errorMessage。
- **问答答“未找到相关信息”**：知识库无命中（阈值过高或未上传文档）；用 `/api/ai/rag/search` 调试 topK 与阈值。
- **切换向量库维度不一致**：同一 Embedding 模型产出的维度必须一致；换模型请清空旧集合/旧库再入库。
