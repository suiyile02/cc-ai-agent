# 方法总览（Method Map）

> 与 [flow-map.md](flow-map.md) 配套：按模块列出**每个类的每个方法做什么、被谁调用**。
> 路径相对 `src/main/java/com/ai/`。调用方标注：**门面**=`ChatService`，**前置**=`ChatPreparationService`，**收尾**=`ChatCompletionService`。
> 表格里 `†` = 该方法当前带状态标注（`【未被引用】`/`【仅测试引用】`/`【已被替代】`），集中清单见文末 §12。

---

## 1. `common/` 业务无关公共层

### `Result<T>` / `PageResult<T>`
| 方法 | 作用 | 调用方 |
|---|---|---|
| `Result.ok()` / `ok(message)` / `ok(data)` / `ok(message,data)` | 统一成功响应 `{code,message,data,timestamp}` | 全部 Controller |
| `Result.ok(String)` † | 仅带消息无 data（历史上 Void 接口用，现统一走 `ok()`） | 无调用方 |
| `Result.fail(ErrorCode)` / `fail(ErrorCode,String)` | 失败响应（由错误码派生 HTTP 状态与文案） | `GlobalExceptionHandler` |
| `Result.fail(int,String)` † | 自定义码值重载 | 无调用方 |
| `PageResult.of(records,total,page,size)` | 由数据+总数构造分页壳（自动算 totalPages） | 各列表 Service |

### `ErrorCode` / `BusinessException`
| 方法 | 作用 |
|---|---|
| `ErrorCode(int code,String message,HttpStatus)` | 定义错误码→默认文案→HTTP 状态的三元映射（1001~1005 参数/文件、2001~2004 资源、4001 冲突、5001~5004 系统/AI、6001~6010 用户与限流并发） |
| `getErrorCode()` `getHttpStatus()` `getMessage()` | 供切面与异常处理器读取 |
| `BusinessException(ErrorCode)` / `(ErrorCode,String)` | 抛业务异常（自定义消息覆盖默认文案） |

### `GlobalExceptionHandler`
| 方法 | 作用 |
|---|---|
| `handleBusiness` | 业务异常 → 按 errorCode 的 HTTP 状态返回统一失败体 |
| `handleMissingParam` / `handleTypeMismatch` / `handleUnreadable` | 参数缺失 / 类型不匹配 / JSON 不可读 → 400 系 |
| `handleValidation` | `@Valid` 失败：汇总所有字段错误为一条中文消息 |
| `handleUploadSize` | multipart 超限 → 1003 |
| `handleMethodNotSupported` | 405 |
| `handleNoResource` | 静态资源不存在 → 404 |
| `handleException(Exception)` | **唯一兜底**：ERROR 记完整堆栈，只回 5001（堆栈不出网）。旁路组件异常必须在各自组件内 catch，不允许落到这里 |
| `respond(ErrorCode,String)` | 构造带状态响应（内部复用） |

### Token 计量
| 方法 | 作用 | 调用方 |
|---|---|---|
| `TokenCounter.count(String)` / `count(List<Message>)` | 计 token（消息口径：每条 +4 开销） | 装配器、摘要器、关键词预算 |
| `TokenCounter.truncateToTokens(text,max)` † | 接口默认实现（估算切点后收缩） | 被 Jtok 覆写 |
| `JtokTokenCounter.count/truncateToTokens` | jtokkit CL100K_BASE 精确计数与"编码→取前 N 词元→解码"精确截断 | 生产默认（`ContextConfig`） |
| `HeuristicTokenCounter.count` † | 零依赖启发式估算（CJK 1~2 词元/字） | 备用实现与测试对照 |
| `MessageTextRenderer.render(messages)` / `roleLabel(type)` | 消息列表 → "角色: 文本" 多行对话稿（喂给摘要器/改写器） | `ConversationSummarizer`、`QueryRewriter` |
| `SensitiveDataMasker.mask(text)` | 落库前打码手机号/邮箱 | `ToolCallLogAspect` |
| `Strings.truncate(text,maxChars)` | 字符安全截断（不切断 emoji 代理对） | 日志字段裁剪 |
| `DateParamUtils.parseDate(String)` | `"yyyy-MM-dd"` → `LocalDate`（非法值→参数错误） | 日志类接口 |
| `Timeouts.call(Supplier,timeoutMs)` | 在虚拟线程上限时执行外部调用；超时/异常统一非受检抛出；内含 `Semaphore(50)` 并发上限 + in-flight/峰值观测 | 检索、改写、重排、解析 |

### `WarnThrottle`（本会话新增）
| 方法 | 作用 | 调用方 |
|---|---|---|
| `of(log)` / `of(log,windowMs)` | 创建节流器（默认 60s 窗口） | 5 个 Redis 组件字段初始化 |
| `warn(format,args…)` | CAS 抢放行权：窗口内只放行一条，下一条追加"窗口内已抑制 N 条同类降级" | 各降级 catch |

---

## 2. `aspect/` 全局切面

### `SelfOrAdminAspect`
| 方法 | 作用 |
|---|---|
| `enforce(pjp, @RequireSelfOrAdmin)` | ADMIN 按传入 `userId` 过滤；非 ADMIN **强制改写**为该参数为当前登录用户 → 数据隔离仍由 Service 的 userId 条件完成 |
| `enforceAdmin(pjp, @RequireAdmin)` | 非 ADMIN → `AUTH_FAILED`(5002/403) |
| 私有：解析参数名/定位 `userId` 参数 | 支撑上面两条 |

### `ToolCallLogAspect`
| 方法 | 作用 | 调用方 |
|---|---|---|
| `around(pjp, @Tool)` | 计时执行工具 → 落 `tool_call_log`（入参/出参/耗时/状态/错误）；**★同时 `toolCalls.incrementAndGet()`（失败调用也计数）** | Spring AI 工具调用反射 |
| `findToolContext(args)` | 从参数里取 `ToolContext`（由门面经 `toolContext` 透传 sessionId/userId/toolCalls） | 内部 |
| `stripToolContext(args)` | 序列化入参前剔除 `ToolContext`（上下文不是业务入参，不落库） | 内部 |
| `toJson(value)` | Jackson 序列化，失败退化 `toString`；结果再经 `SensitiveDataMasker` | 内部 |

---

## 3. `config/` 装配层

| 类 | 方法 | 作用 |
|---|---|---|
| `AppProperties` | `@ConfigurationProperties("app")` 的嵌套段：`Rag`/`Storage`/`Chat`/`Auth`/`Demo`/`Cors`/`Context(+Budget/QueryRewrite/Summary/History)`/`LogRetention`/`SemanticCache`/`IntentCache`/`Ingestion`/`Concurrency` | 全项目配置的唯一载体（含默认值与注释） |
| `AsyncConfig` | `ingestionExecutor()` | 入库线程池 5/20/队列100 + `CallerRunsPolicy`（队列满由提交线程执行，不静默丢任务） |
| | `auditExecutor()` | 审计与滚动摘要线程池（与入库解耦；同时消除多 TaskExecutor 的注入歧义） |
| `ChatClientProvider` | `getIfAvailable()` | 运行时惰性取 `ChatModel` 构建并缓存 `ChatClient`；模型未配置返回 null 由调用方降级 |
| | `modelLabel()` ★ | **当前生效模型名的单一事实来源**（ChatModel 默认选项 → `app.chat.model-label` → `unknown`），供 `chat_log.model_name` 与语义缓存键共用 |
| | `getBuilderIfAvailable()` | 返回可定制请求链的 `ChatClient.Builder`（供 `CompressionQueryTransformer`） |
| `ChatConfig` | `chatMemoryRepository(...)` | 注册 `DbChatMemoryRepository`（写 `SPRING_AI_CHAT_MEMORY`） |
| | `chatMemory(...)` | `MessageWindowChatMemory` 窗口 30 条（被 `ChatSessionService` 用于删除会话时清记忆） |
| `ContextConfig` | `tokenCounter()` | 默认注入 `JtokTokenCounter` |
| `MybatisPlusConfig` | `mybatisPlusInterceptor()` | 分页插件 |
| | `auditMetaObjectHandler()` | `createdAt`/`updatedAt` 自动填充 |
| | `sqlSessionTemplate(...)` | 手工装配（Boot4 下 MP 需手动接线） |
| `JacksonConfig` | `objectMapper()` | 注册 JSR-310 等模块 |
| `CorsConfig` | `addCorsMappings` | `/api/**` 允许配置来源带任意头（Bearer 走头，不用 cookie） |
| `WebAuthConfig` | `addInterceptors` | 注册 `AuthInterceptor` 拦 `/api/**` |
| | `configureAsyncSupport` | SSE 用 Boot 虚拟线程 task executor |
| `DemoDataInitializer` | `run` / `seedUsers` / `seedEmployees` / `seedOrders` / `employee(...)` | 仅空表播种 admin/admin123、张三李四王五、示例订单（prod 关闭） |
| `SecurityConfigValidator` | `run` | prod 三条硬规则：默认密钥 / <32 字节 / `blacklist-fail-open=true` → 抛异常拒绝启动；非 prod 只 WARN |
| `RedisReadinessProbe` ★ | `run` / `probeFailure` / `degradedCapabilities` | 启动探一次 Redis：可用→INFO 口径汇总；不可用→WARN 逐项列降级能力；异常全吸收不外抛 |

---

## 4. `prompt/`

| 方法 | 作用 | 调用方 |
|---|---|---|
| `PromptService.systemFor(type,mode,hasContext,contextText)` | 按会话类型/意图/是否命中上下文选模板并渲染系统提示词（`base-system.st` + `rag-context` 注入） | 门面 `buildSpec` |
| `baseSystem(type)` | 加载基础模板并替换类型占位符 | 内部 |
| `template(path)` | 读模板原文（带缓存），供改写/摘要复用 | `QueryRewriter`、`ConversationSummarizer` |
| `load(path)` | classpath 读取；失败不缓存空串（下次重试） | 内部 |

---

## 5. `rag/` RAG 能力模块

### 模块根（对外契约）
| 成员 | 作用 |
|---|---|
| `RagRetriever`（接口） | `available()` †、`retrieve(query)` †、`retrieve(query,topK,threshold)`、`retrieveOutcome(...)`、`buildContext(hits)` †、`buildContext(hits,tokenBudget)`、`toSources(hits)` |
| `IntentRouter` | `route(message)` → `RagMode` |
| `RagMode` | `KB`/`TOOL`/`GENERAL` 三态 |
| `RetrievalOutcome`（record） | 命中集合 + `semanticHits`/`keywordHits`/`finalHits` + `degraded` 标记；`none()`=未执行、`executedEmpty()` ★=超时/异常降级的"已执行零命中"（保住审计不变式） |
| `SemanticCacheAdmin` / `IntentCacheAdmin` | `evictAll()`：跨模块运维清空契约 |

### `RagRetrievalService`（实现 `RagRetriever`）
| 方法 | 作用 | 调用方 |
|---|---|---|
| `retrieveOutcome(query,topK,threshold)` | 主入口：`Timeouts.call(doRetrieve, retrieve-timeout-ms=10s)`，超时/异常→`executedEmpty()` | 前置 |
| `doRetrieve(...)` | 实际执行体：语义路 + 关键词路 → RRF → 重排 | 内部 |
| `searchVector(store,query,topK,threshold)` | 向量路（`VectorStore.similaritySearch`，阈值过滤） | 内部 |
| `searchKeyword(query,topK)` | BM25 路（`KeywordIndex.search`；索引空则跳过） | 内部 |
| `toDocument(hit)` | 关键词命中 → 与语义命中同构的 `Document`（带 `doc_id/file_name/chunk_index/keyword_score`） | 内部 |
| `fuse(...)` / `rrf(...)` | RRF(k=60) 融合两路排名并按 `doc_id:chunk_index` 去重 | 内部 |
| `scoreFusion(ranked)` | `score` 模式重排：语义 0.6 + BM25 归一 0.4 | 内部 |
| `llmRerank(...)` | `llm` 模式：调模型排序候选，失败回退 score | 内部 |
| `toFinal(c)` | 候选 → 注入文档（挂最终 score） | 内部 |
| `similarityOf` / `keywordScoreOf` / `chunkKeyOf` / `fileNameOf` / `docIdOf` / `chunkIndexOf` / `scoreOf` / `snippetOf` | 元数据与分数读取小工具 | 内部 |
| `buildContext(hits,tokenBudget)` | 按相关度累加拼上下文，受 Token 与字符上限双约束 | 装配器 |
| `toSources(hits)` | 命中 → `SourceVO`（文件名/片段/分数/docId） | 前置（来源与审计） |
| `retrieve(query,topK,threshold)` | 兼容入口（检索调试接口） | 门面 `debugRetrieve` |
| `available()` / 单参 `retrieve` / 无预算 `buildContext` † | 契约保留项，当前无调用方 | — |

### `KeywordIndex`（进程内 BM25）
| 方法 | 作用 | 调用方 |
|---|---|---|
| `addDocument(docId,fileName,chunkDocs)` | 幂等注册整篇（先清旧再写，写锁） | 入库、重建 |
| `addDocumentLocked(...)` | 锁内实现：词频/df/平均文档长度增量维护 | 内部 |
| `removeDocument(docId)` / `removeDocumentLocked` | 删除文档全部分块并回收 df | 删除、重处理 |
| `search(query,topK)` / `searchLocked` | BM25 打分取 Top-K（读锁并发） | 检索关键词路 |
| `termsOf(text)` / `isCjk(c)` | 轻量分词：英文数字词元 + 中文连续二元组 | 内部 |
| `keyOf(docId,chunkIndex)` | 分块主键 `doc:chunk` | 内部 |
| `isEmpty()` / `size()` | 可用性判断 / 规模（`size()` † 仅测试） | 检索、重建、测试 |

### `KeywordIntentRouter` / `CachingIntentRouter`
| 方法 | 作用 | 调用方 |
|---|---|---|
| `KeywordIntentRouter.route(message)` | 三态判定：工具词 → TOOL（无条件）→ 内部词 → KB → 否则 GENERAL | 前置 |
| `isToolQuestion(message)` | 命中 `app.rag.tool-keywords` 即为工具问题 | 内部 |
| `looksInternal(message)` | 命中 `app.rag.internal-keywords` | 内部 |
| `CachingIntentRouter.route(message)` | 先读 `rag:intent:v{版}:{sha256}`，未命中调真实路由并回写（TTL 60min）；**Redis 异常整段按未命中处理** | 全局 `@Primary` 注入点 |
| `evictAll()` | 版本自增整体失效（管理员接口） | `SystemController` |
| `currentVersion` / `key` / `normalize` / `digest` | 版本读取与键构造（归一化与语义缓存同口径） | 内部 |

### `SemanticAnswerCache`（实现 `SemanticCacheAdmin`）
| 方法 | 作用 | 调用方 |
|---|---|---|
| `get(question)` | 读正缓存；未命中/未启用/异常→null（命中打 INFO 耗时） | 前置 |
| `put(question,content,sources)` | 写正缓存（TTL `ttl-hours`） | 收尾 |
| `isMiss(question)` / `putMiss(question)` | 负缓存读写（TTL `miss-ttl-minutes`） | 前置 / 收尾 |
| `evictAll()` | `INCR rag:kb:version` → 旧命名空间整体失效，返回新版本 | 知识库增删/重处理、管理员清空 |
| `answerKey` / `missKey` / `namespace` / `modelTag` ★ | 键构造：`v{版本}:m{模型}:{摘要}`，模型名清洗为 Redis 友好字符 | 内部 |
| `normalize` / `digest` | 归一化（去空白+小写）与 SHA-256 | 内部、测试 |
| `enabled` / `currentVersion` | 开关与版本读取（缺失=0） | 内部 |

### `KeywordIndexRebuilder`
| 方法 | 作用 |
|---|---|
| `onApplicationReady(event)` | 受 `app.rag.auto-rebuild-index` 控制，起守护线程重建（不阻塞启动） |
| `rebuild()` | 滚动分页读 Qdrant（PAGE_SIZE=1000）→ 按 doc 分组 → `addDocument`；索引非空则跳过 |
| `stringValue/longValue/intValue` | Qdrant payload JSON 值安全转换 |

---

## 6. `context/` 上下文管线

### 契约与 record
| 成员 | 作用 |
|---|---|
| `ConversationMemory` | `loadHistory`/`append`/`messageCount`/`recentMessages`/`clearSummary`/`summaryOf`/`summarizeIfNeededAsync` |
| `HistoryContext` | `messages` + `summary`；`empty()` †（无调用方） |
| `AssembledPrompt` | 装配产物：系统提示词、历史文本、RAG 文本、最终 user 文本、`composition` 快照 |
| `ContextComposition` | 四段 token 占用 + 是否截断（落 `context_log`） |
| `ConversationSummary`(entity) / `ConversationSummaryMapper` | `conversation_summary` 表与 Mapper（`summary` 文本 + 覆盖范围） |

### `ConversationMemoryService`（实现 `ConversationMemory`）
| 方法 | 作用 | 调用方 |
|---|---|---|
| `loadHistory(sessionId,historyTokenBudget)` | 取窗口消息并做 Token 收敛（前置摘要段）；**请求路径零 LLM** | 装配器 |
| `append(sessionId,user,assistant)` | 写回一轮（**append-only**，`ChatMemoryAppender`；与摘要裁剪同用 per-session 锁） | 收尾 |
| `summarizeIfNeededAsync(sessionId)` | `@Async(auditExecutor)`：超阈值则生成摘要并裁剪 | 收尾 |
| `messageCount(sessionId)` | 走 `COUNT(*)`（`ChatMemoryCounter`），不反序列化历史 | 改写触发判定 |
| `recentMessages(sessionId,limit)` | 最近 N 条（改写参考） | `QueryRewriter` |
| `summaryOf` / `readSummary` / `writeSummary` | 摘要读写（upsert） | 装配、裁剪、会话回显 |
| `clearSummary(sessionId)` | 删除会话时清理 | `ChatSessionService` |
| `lockFor(sessionId)` | 取/建会话级锁对象（Caffeine 有界，防泄漏） | 内部 |
| `summaryTokens(summary)` | 摘要段 token（含 SYSTEM 注入开销） | 内部 |

### `ConversationSummarizer` / `QueryRewriter`
| 方法 | 作用 |
|---|---|
| `summarize(existing,oldMessages)` | 合并"已有摘要 + 新增老消息"生成新摘要（disable-thinking、`Timeouts` 限时） |
| `SummaryResult.unchanged(s)` | 未更新时的结果 |
| `rewrite(sessionId,sessionType,userMessage)` | 指代消解：非 AGENT + 有历史 + 含线索词 + 未冷却 才调 `CompressionQueryTransformer`；失败/超时回退原问题 |
| `resolveTransformer()` | 注入优先，否则按 ChatClient 可用性惰性构建 |
| `containsReferenceHint(message)` | 线索词表判定（`app.context.query-rewrite.reference-hints`） |

### `ContextAssembler`
| 方法 | 作用 |
|---|---|
| `assemble(session,userMessage,ragContext,rewriteResult)` ★ | 四段统一预算切分（system/history/rag/user），产出 `AssembledPrompt` + `ContextComposition` |
| `tailTruncate(text,maxTokens)` | 保留尾部截断（问题常在末尾） |

---

## 7. `chat/` 对话模块

### Controller 与 DTO
| 成员 | 作用 |
|---|---|
| `ChatController.chat/chatStream/ragSearch` | 端点 5/6/7；`chatStream` 用 `Flux<ServerSentEvent<String>>` 并追加 `data:[DONE]` |
| `ChatRequest` | `sessionId`+`message`（Bean Validation） |
| `ChatResponse` | `{content}`（引用来源不再返回） |
| `ChatStreamEvent` | `EventType.CONTENT`（唯一） + `[DONE]` |
| `RagDebugRequest` / `RagDebugResponse` † | 调试入参 / 已废弃的嵌套响应 record |
| `SourceVO` | 来源：文档名、片段、分数、docId |
| `ChatCompletedEvent` / `ChatDecisionEvent` | 审计事件负载（record，含 composition/usage/modelLabel） |

### `ChatService`（门面）
| 方法 | 作用 |
|---|---|
| `chat(sessionId,userMessage,userId)` | 同步：名额 → `requireActive` → 前置 → 缓存命中直返 / `buildSpec.call()` → 收尾 → finally 释放名额 |
| `chatStream(...)` | 流式：名额 + 前置在 `boundedElastic`；命中→单事件；否则 `streamAnswer`；`doFinally` 释放名额 |
| `streamAnswer(...)`（private） | 订阅模型流：累积文本 + `AtomicReference` 收 usage（含末尾"只带 usage"的分块）+ `timeout(stream-idle-timeout-ms)` 静默终止；成功走 `finishStream`、异常走 `completeInterrupted` |
| `finishStream(...)`（private） | 全文 + usage 交收尾，映射为 `CONTENT` 事件 |
| `buildSpec(client,session,prompt,stream,toolCalls)` ★ | 统一请求链：system/历史/user、`needTools` 时挂 `BusinessTools` 与 `toolContext{sessionId,userId,toolCalls}`、`stream` 时 `streamUsage(true)`、`disable-thinking` 的 `extraBody` |
| `needTools(session)` | AGENT/HYBRID 才挂工具 |
| `requireChatClient()` | 模型未配置 → 友好降级异常 |
| `extractText(response)` / `usageTotal(response)` | 从响应取正文/`usage.totalTokens`（`result` 为空的 usage-only 分块仍可取） |
| `debugRetrieve(question,topK,threshold)` | 忽略意图路由直接检索（调参用） |

### `ChatPreparationService`（前置，同步与流式共用）
| 方法 | 作用 |
|---|---|
| `prepare(session,userMessage)` | 编排 §8 全部步骤；new `AtomicInteger toolCalls` ★ |
| `resolveRagContext(session,userMessage)` | AGENT 不检索；否则路由（TOOL 无条件跳过检索）→ KB 才检索 |
| `cacheEligible(session,rw,retrievalQuery)` | enabled && 未改写 && 非 AGENT && route==KB |
| `publishDecision(session,userMessage,rag,costMs)` | 发 `ChatDecisionEvent`（模型失败也留痕） |
| `PreparedChat`（record） | `rw`/`rag`/`sources`/`assembled`/`cacheEligible`/`retrievalQuery`/`cachedAnswer`/`toolCalls` ★ |
| `RagContext.empty()` | 未检索时的空上下文 |

### `ChatCompletionService`（收尾，同步与流式共用）
| 方法 | 作用 |
|---|---|
| `complete(session,userMessage,prep,answer,usage,startMs)` | 记忆写回 → 摘要触发 → 完成事件 → ★缓存闸门（`toolCalls>0` 跳过正负缓存；否则 `cacheEligible && mode==KB` 内按 `declaresNoResult` 决定 `put` 或 `putMiss`）→ 来源落库 |
| `completeCached(session,userMessage,cached,startMs)` | 命中路径收尾：写记忆与审计，不写模型耗时口径的缓存 |
| `completeInterrupted(session,userMessage,answer,startMs)` | 中断收尾：只写记忆/审计，**禁止**触达缓存写入 |
| `publishCompleted(...)` | 组 `ChatCompletedEvent`，`modelLabel()` 取 `chatClientProvider.modelLabel()` ★ |

### `ChatConcurrencyGuard` / `ChatSourceDisplay`
| 方法 | 作用 |
|---|---|
| `ChatConcurrencyGuard.acquire(userId)` | Caffeine per-user `Semaphore(max=3)` 限时 `tryAcquire`，超时抛 6010/429；返回 `Handle`（`close()` 释放） |
| `ChatSourceDisplay.sourceNames(sources)` | 去重保序 + 去扩展名 → 来源文档名列表（落 `chat_log.sources`） |
| `stripExtension(name)` | `"员工手册示例.md"` → `"员工手册示例"` |
| `declaresNoResult(answer)` | 判断回答是否声明"未找到"（此类答案不写正缓存） |
| `NO_RESULT_ANSWER` 常量 | 负缓存命中时的固定文案（与 `base-system.st` 口径一致） |

---

## 8. `knowledge/` 知识库模块

| 类 | 方法 | 作用 |
|---|---|---|
| `KnowledgeController` | `upload` / `uploadBatch` / `listDocuments` / `deleteDocument` / `reprocess` | 端点 8~12；**Controller 层无 `@Require*`**，删除/重处理的 `@RequireAdmin` 挂在 Service（见 flow-map §22） |
| `KnowledgeDocumentService` | `upload(file,userId)` | §6 校验链 → 落盘 → `status=0` 落库 → 事务后触发入库 |
| | `uploadBatch(files,userId)` | 逐文件走同一链，部分失败语义 |
| | `listDocuments(pageNum,pageSize,…)` | 分页 + `status`/关键字过滤，返回 VO |
| | `deleteDocument(id)` | 清向量 → 清关键词索引 → 删记录 → 尽力删文件 |
| | `reprocess(id)` | 清旧向量与索引 + 状态重置 → 重新入库 |
| | `ingestAfterCommit(docId)` + `afterCommit()` | **事务提交后**再投 `@Async`（否则异步线程读不到未提交记录） |
| | `requireDocument(id)` | 存在性校验（1004） |
| | `deleteVectorPoints(documentId)` | 按 `doc_id` filter 精确删点（向量库不可用→记日志继续） |
| | `toVO(d)` | 实体 → VO（含 `errorMessage` 友好化） |
| `DocumentIngestionService` | `ingestDocument(docId)` | `@Async("ingestionExecutor")`：状态机推进 + 解析分块 + 向量化入库 + 注册 BM25 + `evictAll()` |
| | `parseAndSplit(doc)` | Tika 读 + `TokenTextSplitter(512/100)`，`Timeouts.call(parse-timeout-ms)` |
| | `addMetadata(doc,chunks)` | `doc_id`/`file_name`/`chunk_index`/`collection`（删除与溯源依据） |
| `FileStorageService` | `save(file,userId)` | `用户/日期/UUID.ext` 落盘 |
| | `delete(path)` | 尽力而为（失败仅 WARN） |
| | `validateContent(file,ext)` | 魔数与扩展名一致性（PDF/ZIP/docx 头） |
| | `checkZipBomb(file)` | 流式遍历 zip 条目：解压后 ≤512MB、条目 ≤5000 |
| | `extensionOf(name)` | 小写无点扩展名 |
| entity/mapper/dto | `KnowledgeDocument`(表 `knowledge_document`, `status` 0/1/2/3 + `error_message` 注释)、`KnowledgeDocumentMapper`、`KnowledgeUploadVO`、`KnowledgeDocumentVO`、`BatchUploadResultVO` | 数据与响应载体 |

---

## 9. `user/` 用户与鉴权

| 类 | 方法 | 作用 |
|---|---|---|
| `AuthController` | `register` / `login` / `logout` / `me` | 端点 1~4；`clientIp(request)` 优先取反代头 |
| `AuthService` | `register(request)` | 查重(6001) → PBKDF2 哈希 → 插入 → 直接返回登录态 |
| | `login(ctx)` | §5 全链（锁定判定 → 校验 → 失败计数 → status 校验 → 签发） |
| | `logout(payload,remaining)` | `ban(jti,remaining)` |
| | `me(userId)` / `buildAuthVO` / `existsByUsername` / `findByUsername` / `toVO` | 查询与装配（禁止返回实体） |
| `JwtTokenProvider` | `createToken(userId,username,role)` | HS256 + `jti`（UUID）+ 过期 |
| | `parse(token)` → `TokenPayload(userId,username,role,jti,expiresAt)` | 校验签名与过期，失败抛业务异常 |
| `PasswordHasher` | `encode` / `matches` / `pbkdf2` | PBKDF2-HmacSHA256 + 随机盐（迭代次数写在类常量） |
| `UserContext` | `set`/`get`/`clear`/`currentUserId`/`requireUserId`；`CurrentUser.isAdmin()` | ThreadLocal 当前用户；请求结束必须 `clear` |
| `AuthInterceptor` | `preHandle` / `writeUnauthorized` | §4 全链 |
| `LoginAttemptLimiter`（契约） | `isLocked`/`recordFailure`/`recordSuccess`/`remainingLockMs` | 双维度限流；两实现按 `app.auth.rate-limit-backend` 选择 |
| `RedisLoginAttemptLimiter` | 同上 + `fail`/`del`/`ttl`/`lockKey` | Redis 键 `login:fail:*`/`login:lock:*`；**读写两侧均 catch ★**，MAX=5、窗口 10min、锁 5min |
| `InMemoryLoginAttemptLimiter` | `recordFailure(u,ip)` / `recordFailure(u,ip,now)` † / `evictStale(now)` / `trackedEntries()` † / `locked`/`remaining`/`fail`/`ipKey` | 进程内滑动窗口；★超过 `EVICT_THRESHOLD(1000)` 顺带清理过期条目 |
| `TokenBlacklistService` | `ban(jti,ttlMs)` / `isBanned(jti)` | `jwt:black:{jti}`；Redis 异常按 `blacklist-fail-open` 决定 |
| `RequireAdmin` / `RequireSelfOrAdmin` / `LoginContext` | 注解与 `record(request,clientIp)` | 授权声明与登录入参 |
| `SysUser`(entity) / `SysUserMapper` / `AuthVO`/`UserVO`/`LoginRequest`/`RegisterRequest`（含 `effectiveNickname()`） | — | 表 `sys_user`（含 `role`）与 DTO |

---

## 10. `session/` 会话模块

| 方法 | 作用 | 调用方 |
|---|---|---|
| `ChatSessionService.create(title,type,userId)` | 生成 UUID `sessionId`（=记忆与日志的 conversation_id） | Controller |
| `list(userId,pageNum,pageSize)` | 本人会话分页倒序 | Controller |
| `listMessages(id,userId)` | 回显历史 + 滚动摘要（归档也可看） | Controller |
| `archive(id,userId)` / `delete(id,userId)` | 软删 `status=0`；delete 另清记忆与摘要；两者 `SessionCacheService.evict` | Controller |
| `requireActive(sessionId,userId)` | 存在 + 进行中 + 归属校验（对话每轮的入口守卫，§16） | 门面 |
| `requireOwned(id,userId)` / `toVO(s)` | 归属校验 / 实体转 VO | 内部 |
| `SessionCacheService.get/put/isNotFound/putNotFound/evict` | `session:meta:*`(10min) + `session:absent:*`(1min)；**全部降级安全 + WARN 节流** | `requireActive`、增删改 |
| `ChatSession`(entity)/`ChatSessionMapper`、`SessionCreateRequest.effectiveType()`、`SessionVO`/`SessionMessagesVO`/`HistoryMessageVO` | 表 `chat_session` 与 DTO（类型归一化 HYBRID） | — |

---

## 11. `system/` 审计模块 · `agent/` 工具 · `memory/` 记忆存储

### `system/`
| 方法 | 作用 | 调用方 |
|---|---|---|
| `SystemController.listChatLogs/listToolCallLogs/listRagDecisions/listContextLogs` | 端点 18~21，分页 + 时间/维度过滤，`DateParamUtils` 解析 | 前端 SystemView |
| `SystemController.clearSemanticCache/clearIntentCache` | 端点 22~23，返回失效后的版本号 | 运维/前端 |
| `ChatAuditListener.onDecision(event)` | `@Async(auditExecutor)` 写 `rag_decision_log` | 前置事件 |
| `ChatAuditListener.onChatCompleted(event)` | 写 `chat_log`（来源序列化 JSON）+ `context_log`（仅管线模式有快照） | 收尾事件 |
| `ChatAuditListener.recordChatLog` / `recordContextLog` | 组装并保存 | 内部 |
| `ChatLogService.record(session,user,answer,sources,model,duration,usage,rewrite,mode,composition)` | 落一条对话日志 | 监听器 |
| `RagDecisionLogService.save/list`、`ContextLogService.save/list`、`ToolCallLogService.save/list` | 各表写入与分页查询 | 监听器 / 切面 / Controller |
| `LogCleanupScheduler.cleanup()` | `@Scheduled(cron=app.log-retention.cron)` 按保留天数物理删四类日志（`enabled` 可关） | 定时 |
| 4 个 entity + 4 个 mapper + 4 个 VO | `chat_log`(含 `sources` JSON、`total_tokens`)、`tool_call_log`、`rag_decision_log`、`context_log` | — |

### `agent/`
| 方法 | 作用 |
|---|---|
| `BusinessTools.queryEmployee(name,toolContext)` `@Tool` | 按姓名查员工部门/职位/电话/邮箱；未找到返回中文说明 |
| `BusinessTools.queryOrder(orderNo,toolContext)` `@Tool` | 按订单号查状态/金额/物流单号 |
| `BusinessTools.getCurrentTime(toolContext)` `@Tool` | 当前日期时间（回答"现在几点"） |
| `toVO(Employee)` / `toVO(SalesOrder)` | 实体 → VO；`EmployeeVO.notFound(name)` / `OrderVO.notFound(no)` 生成"未找到"结果 |
| `Employee`/`SalesOrder`(entity) + 两个 Mapper | 演示业务表 `employee`/`sales_order` |

### `memory/`
| 方法 | 作用 | 调用方 |
|---|---|---|
| `DbChatMemoryRepository.findByConversationId(cid)` | 按 `timestamp,id` 升序回放（`readText` 兼容旧纯文本 content） | 装配、摘要、回显 |
| `append(cid,newMessages)`（`ChatMemoryAppender`） | **只 INSERT 新增行**，保留真实时间戳 | 收尾写回 |
| `countByConversationId(cid)`（`ChatMemoryCounter`） | `COUNT(*)` 不加载内容 | 改写触发判定 |
| `saveAll(cid,messages)` | 先删后插——仅摘要**裁剪后**重写窗口时使用 | `ConversationMemoryService:152` |
| `findConversationIds()` / `deleteByConversationId(cid)` | 会话枚举 / 删会话记忆 | 契约与删除会话 |
| `toJson(text)` / `toMessage(record)` / `JsonText` | content 列 JSON 序列化与还原 | 内部 |
| `ChatMemoryRecord`(entity) / `ChatMemoryRecordMapper` | 表 `SPRING_AI_CHAT_MEMORY` | — |

---

## 12. 状态标注汇总（可直接 grep 复核）

```bash
grep -rn "【已被替代】\|【未被引用】\|【仅测试引用】" src/main/java
```

| 位置 | 标注 | 说明 |
|---|---|---|
| `rag/RagRetriever.java:22,33,63` + `RagRetrievalService.java:61,73,433` | 【未被引用】 | `available()`、单参 `retrieve`、无预算 `buildContext` 仅实现契约，无调用方 |
| `chat/dto/RagDebugRequest.java:38` | 【未被引用】 | 嵌套 `RagDebugResponse` record 无引用点，可删 |
| `context/HistoryContext.java:22` | 【未被引用】 | `empty()` 无调用方（装配层始终走真实历史） |
| `common/Result.java:38,113` | 【未被引用】 | `ok(String)` 与 `fail(int,String)` 无调用方 |
| `common/HeuristicTokenCounter.java:13`、`common/TokenCounter.java:44` | 【已被替代】 | 生产默认 `JtokTokenCounter`；保留为备用实现与测试对照 |
| `knowledge/service/KnowledgeDocumentService.java:261` | 【已被替代】 | 旧"扫 1 万点再删"两步法已被 `doc_id` filter 精确删除取代 |
| `rag/service/KeywordIndex.java:139` | 【仅测试引用】 | `size()` 生产用 `isEmpty()` |
| `user/security/InMemoryLoginAttemptLimiter.java:78,134` | 【仅测试引用】 | 时钟重载与 `trackedEntries()` 供内存增长断言 |
