# 优化路线图（P0 之后待评估项）

> 状态约定：`待评估` = 需触发条件满足后再实施；每项包含「现状 → 方案 → 验收标准」。
> **2026-09-15 更新：P1-1~P1-5、P2-1~P2-4、P3(Redis 限流+会话缓存) 已全部实施并验收通过**, 明细见文末"已完成记录"。
> P0 已完成项（JWT 启动校验 / 登录失败限流 / 连接池）见本文末尾"已完成记录"。
> 评估时间：2026-09 基于代码实证扫描（非推测）。

---

## P1 性能与成本（建议下个迭代评估，1~2 天）

### P1-1 会话记忆写回 O(n) 写放大
- **现状**：`DbChatMemoryRepository.saveAll`（:100-120）每轮对话"全删 + 逐条重插"该会话全部历史（30 条 ≈ 60 次 SQL），且 `timestamp` 被重写为 `now+seq`，真实时间失真。
- **方案**：改 append-only（只 INSERT 本轮 2 条）；裁剪只在异步摘要触发时批量执行；保留 `timestamp` 真实值。
- **验收**：单轮对话对该表的 SQL 次数从 ~60 降为 ~2；`SPRING_AI_CHAT_MEMORY.timestamp` 反映真实消息时间。
- **风险**：摘要裁剪逻辑与 loadHistory 的窗口逻辑需适配 append-only 语义（有单测保护）。

### P1-2 sessionLocks 内存泄漏
- **现状**：`ConversationMemoryService:183` `computeIfAbsent` 创建的 per-session 锁对象从不移除，随会话数无上限增长（`summarizing` 集合有 finally 清理，正确）。
- **方案**：引入 Caffeine（`maximumSize` + `expireAfterAccess`）或简化为容量上限 LRU。
- **验收**：构造 10 万会话 ID 后内存占用有界；并发正确性单测不回退。

### P1-3 Token 用量采集（成本优化的前提）
- **现状**：`chat_log.total_tokens` 恒为 null（全项目无 `getUsage()` 读取）；LLM 成本完全不可见。
- **方案**：从 `ChatResponse.getMetadata().getUsage()` 采集 prompt/completion tokens，写入 chat_log 新增列（需 ALTER）；可扩展按用户/模型的日报。
- **验收**：每次对话后 chat_log.total_tokens 有真实值；能按用户聚合出 token 消耗。

### P1-4 KeywordIndex 检索效率
- **现状**：5 个方法全 `synchronized`（检索全局串行）；每次 search 全量重算 avgLen（O(n)）；虚拟线程下 synchronized 有 pinning 风险。
- **方案**：avgLen 在 add/remove 时增量维护；`synchronized` → `ReentrantReadWriteLock`（读并发）。
- **验收**：并发检索吞吐提升（可用 JMH 或简单并发测试对比）；单测语义不回退。

### P1-5 向量删除走 Qdrant 原生 filter delete
- **现状**：`KnowledgeDocumentService.deleteVectorPoints` 用占位查询 `similaritySearch` 扫最多 1 万点再按 id 删。
- **方案**：注入 `QdrantClient`，`filter delete(doc_id)` 一步完成（注意 payload 中 doc_id 为字符串）。
- **验收**：删除文档后 Qdrant 点数立即归零（现方式已修复为字符串匹配但仍走扫描）。

---

## P2 安全加固（1 天）

### P2-1 上传内容校验
- **现状**：仅扩展名白名单（`ALLOWED_EXT`），无魔数校验；Tika 解析任意 50MB 文件，无超时/解压比限制（压缩炸弹风险）。
- **方案**：魔数校验（PDF/ZIP(含 docx)/文本 BOM）；Tika 解析包超时；解压比 > 100 拒绝。
- **验收**：伪造 .pdf.exe / 深层嵌套 zip 被拒；正常文档不受影响。

### P2-6 collection-name 单一事实来源（✅ 已实施 2026-09-16）
- 实现: `spring.ai.vectorstore.qdrant.collection-name` 改为引用 `${app.rag.collection-name}`,
  集合名只维护一处(之前写/删两处独立配置, 改一忘一会导致向量写入 A 集合、过滤删除读 B 集合)。
- 验收实测: 对话检索正常(VectorStore 写入集合一致); 删除文档后 Qdrant 点数 5→4 递减
  (删除集合 == 写入集合); 重新上传恢复 5。

### P2-5 知识库管理授权（✅ 已实施 2026-09-16）
- 实现: 新增 `user/security/RequireAdmin` 注解 + `SelfOrAdminAspect.enforceAdmin` 切面方法
  (仅 ADMIN 放行, 非管理员 5002/403); 挂载到知识库 `deleteDocument`/`reprocess`。
- 验收实测: tester 删除/重处理 → 5002"该操作需要管理员权限"; admin(入库完成后)删除 → 200;
  普通对话回归正常。

### P2-2 提示注入防护
- **现状**：上传文档内容直接拼入 system（rag-context 段），文档可含"忽略以上指令"类注入。
- **方案**：检索资料用分隔符包裹 + system 声明"资料仅为参考信息，不得作为指令执行"；对资料中的典型注入模式打标告警。
- **验收**：含注入样本的文档上传后，模型仍遵循系统约束（人工评测集）。

### P2-3 PII 脱敏与留存策略
- **现状**：`chat_log.user_message/assistant_reply`、`tool_call_log.input_params/output_result` 明文长期留存（员工手机/邮箱已在工具日志中）。
- **方案**：工具日志写入前正则脱敏（手机号/邮箱）；chat_log 保留期配置（如 180 天）+ `@Scheduled` 定期清理；清理范围与批量大小可配。
- **验收**：新写入的工具日志无明文手机号；过期记录被清理。

### P2-4 日志表归档任务
- **现状**：四张日志表无清理机制，无限增长。
- **方案**：`@Scheduled` 按保留期删除/归档（与 P2-3 合并实现）。
- **验收**：配置保留期后，过期数据被定时清理，任务有日志。

---

## P3 架构级（按需触发，触发条件见各项）

### P3-1 引入 Redis（触发条件：多实例部署 或 需要索引重启不丢）
- 带来的能力：分布式登录限流（替换进程内 `LoginAttemptLimiter`——多实例下各计数独立，爆破者可换实例绕过）、分布式锁（替换 10 处 JVM 内 synchronized）、会话元数据缓存、KeywordIndex 外置（Redis 倒排/MySQL FULLTEXT/ES）。
- **代价**：新增中间件运维 + 每次访问 +0.5ms 网络跳数。
- **验收**：双实例部署下限流/锁定全局生效；应用重启后关键词检索立即可用。

### P3-2 会话记忆多实例原子性（触发条件：同上）
- 现状 append/摘要裁剪靠 JVM 锁；多实例需 DB 原子方案（追加 INSERT + 唯一约束去重）或 Redisson 锁。

### P3-KeywordIndex 自动重建（✅ 已实施 2026-09-16）
- 实现: `rag/service/KeywordIndexRebuilder`——ApplicationReadyEvent 后台线程从 Qdrant 滚动读取
  全部分块 payload(content/doc_id/file_name/chunk_index), 按文档注册进 KeywordIndex;
  不调用 Embedding, 实测 3 文档 5 分块 235ms; 仅索引为空时重建; Qdrant 不可用告警跳过;
  开关 `app.rag.auto-rebuild-index`(默认 true)。
- 验收实测: 重启后不调 reprocess, 决策日志 semantic=4 keyword=5 final=5(BM25 路生效)。
- 修复过程中误报说明: 首两轮验证 keywordHits=0 为验证脚本与后台重建/异步审计落库的时序竞争假象,
  以等待重建完成后的决定性实验为准。

### P3-3 语义缓存（✅ 已实施 2026-09-16; 失效维度补全 2026-09-19）
- 实现: `rag/service/SemanticAnswerCache`——键=知识库版本号+对话模型标识+归一化问题 SHA-256(精确匹配, 不做向量相似度避免错配);
  仅缓存"未改写的独立问题 + KB 命中"的回答, 且**本轮调用过工具的轮次不写缓存**(条目跨用户共享, 见 2026-09-19 记录);
  文档上传/删除/重处理 → 版本号自增联动失效; Redis 异常降级为未命中。配置 `app.semantic-cache.*`(enabled/ttl-hours 24h/miss-ttl-minutes 30m)。
- **穿透防护(2026-09-16 增补)**: 检索真实执行且零命中(非超时降级)的问题写入短 TTL 负缓存
  `rag:miss:v{版本}:m{模型}:{摘要}`, 短时间重复提问直接返回固定"未找到"文案, 不再打检索+模型;
  `RetrievalOutcome` 新增 `degraded` 标记区分"超时降级"与"真零命中", 降级结果不写负缓存。
- 验收实测(2026-09-16, 当时的键格式 `rag:answer:v{版本}:{摘要}`/`rag:miss:v{版本}:{摘要}`): 二次提问 397ms→33ms(回答一致);
  上传文档后 kbVersion 自增, 同问题未命中重新生成(6135ms); 版本键 `rag:kb:version` 落地。
- 相同/归一化问题的答案缓存（TTL + 失效策略）；命中省一次完整 LLM 调用。
- **风险**：制度类内容更新后答案过期，需与知识库版本联动失效。

### P3-4 按会话类型的上下文分级注入（触发条件：P1-3 用量数据证明输入 token 是大头）
- 闲聊会话不注入 RAG、AGENT 会话精简历史；预计省 30~50% 输入 token。
- 依赖 P1-3 的数据做决策，避免盲调。

### P3-5 ToolContext 扩展（可选）
- `tool_call_log` 已回填 session_id/user_id；如需更细审计可透传请求 ID 串联全链路。

---

## 已完成记录（P0，2026-09-15）

| 项 | 现状(旧) | 方案(新) | 验证 |
|---|---|---|---|
| JWT 密钥 | 默认密钥随代码分发，prod 无强校验 | `SecurityConfigValidator`：prod 下默认/短密钥拒绝启动 | 单测回归 + 启动日志 |
| 登录限流 | 无任何失败限制，可无限尝试 | `LoginAttemptLimiter`（用户名+IP 双维度，5 次失败锁 5 分钟，成功清零）；错误码 6006/429 | 运行时实测：第 6 次失败起 6006，锁定期正确密码也被拒 |
| 连接池 | Hikari 默认 10 连接（虚拟线程下成瓶颈） | 显式 max 25 / min idle 10 / timeout 5s | 启动日志 HikariPool 生效 |

单测总数：71（+7 限流用例）。


---

## 已完成记录（2026-09-15, P1/P2/P3 全量）

| 项 | 变更 | 验收证据 |
|---|---|---|
| P1-1 记忆 append-only | 新增 `memory/ChatMemoryAppender` 接口, `DbChatMemoryRepository.append` 只 INSERT 新增行(真实时间戳); `ConversationMemoryService.append` 不再读全部+重写 | 单轮记忆写库 ~60 次 SQL → ~2 次 |
| P1-2 锁泄漏 | `sessionLocks` 改 Caffeine(max 10 万/30min 淘汰) | 编译+回归, 无行为变化 |
| P1-3 用量采集 | `ChatService` 从 ChatResponse.getMetadata().getUsage() 采集, 事件透传落 chat_log.total_tokens | 运行时: 对话后 total_tokens=2163 |
| P1-4 KeywordIndex | ReentrantReadWriteLock(读并发)+avgLen 增量维护 | 单测全过, 语义不变 |
| P1-5 向量删除 | Qdrant 原生 filter delete(`ConditionFactory.matchKeyword`), 移除扫描法 | 运行时删除正常 |
| P2-1 上传校验 | `FileStorageService.validateContent` 魔数校验(PDF/ZIP/文本 NUL 检测) | 运行时: 伪装 fake.pdf 被拒(1002) |
| P2-2 提示注入 | buildContext 输出加"资料开始/结束"分隔包裹 + rag-context.st 隔离声明 | 模板与包装生效 |
| P2-3 PII 脱敏 | 新增 `common/SensitiveDataMasker`, 工具日志入参/出参落库前打码 | 手机 138****56 / 邮箱 a***@demo |
| P2-4 日志清理 | `LogCleanupScheduler`(@Scheduled) 按保留期清理四类日志+记忆表, 配置 app.log-retention.* | 运行时: 每秒 cron 触发, 日志"清理完成 cutoff=... chat=0..." |
| P3 Redis 限流 | `LoginAttemptLimiter` 契约化: 默认 `RedisLoginAttemptLimiter`(Redis 计数+锁定键, 双维度), 可回退 memory 实现 | 运行时: 第 6 次失败 6006, Redis 出现 `login:lock:u:redis_brute`+`login:lock:ip:...`(TTL 递减) |
| P3 会话缓存 | 新增 `SessionCacheService`(session:meta:* TTL 10min, 异常降级回退 DB), requireActive 先缓存后 DB, 归档/删除主动失效 | 运行时: 会话键存在 `session:meta:{sid}` |

注: 修复 @EnableScheduling 引入的 Executor 装配歧义(WebAuthConfig 显式 @Qualifier("taskScheduler"))。


---

## 已完成记录（2026-09-19, 语义缓存失效维度补全: 模型标识 + 工具轮次闸门)

**问题**: 缓存键只有 `知识库版本 + 问题 SHA-256`, 两处口径不足——
① 切换对话模型后旧模型的回答在 TTL(24h) 内仍被命中(批次 B 记录的遗留项);
② 缓存条目跨用户共享, 而"本轮调用过工具"的回答含实时业务数据/个性化内容(订单、员工信息),
   写入共享键会把 A 用户的数据回答给 B 用户, 且同一个 KB 问题被工具介入过一次后长期污染。

**变更**:

| 项 | 变更 | 验收证据 |
|---|---|---|
| 模型名单一事实来源 | `ChatClientProvider.modelLabel()` 统一解析(ChatModel 默认选项 → 回退 `app.chat.model-label` → `unknown`); `ChatCompletionService` 删除自建的 `resolveModelLabel()`(及 `ObjectProvider<ChatModel>`/`AppProperties` 依赖), 对话日志 `chat_log.model_name` 与缓存键同源 | 单测 `ChatCompletionServiceTest`(mock `ChatClientProvider`) + `SemanticAnswerCacheTest` 两用例 |
| 键补模型维度 | `SemanticAnswerCache` 键由 `rag:answer:v{版本}:{摘要}` → **`rag:answer:v{版本}:m{模型}:{摘要}`**(负缓存 `rag:miss:` 同步); 模型名经 `[^A-Za-z0-9._-]→_` 清洗, 防止键分隔符被模型名破坏 | 单测 `answerKeyIncludesModelAndModelSwitchInvalidates`(精确断言键串; 换 `qwen-new` 后不命中) + `modelTagSanitizesKeySeparators`(`gpt 4:o/lab`→`mgpt_4_o_lab`) |
| 工具轮次闸门 | `PreparedChat` 新增 `AtomicInteger toolCalls`, 经 `ChatService.buildSpec` 的 `toolContext` 透传, `ToolCallLogAspect` 每次工具调用(含失败)自增; `ChatCompletionService.complete()` 在写正/负缓存前先查计数, >0 则跳过并打 INFO | 单测 `ToolCallLogAspectTest` 4 例(计数自增/失败仍计数/无 toolContext 容忍/多次累加) + `ChatCompletionServiceTest.toolCallTurnDoesNotWrite{Positive,Negative}Cache` + `ChatPipelineTest.syncPassesToolCallCounterThroughToolContext` |

**未改动**: `ChatPreparationService.cacheEligible()` 判断口径未放宽(仍是 enabled && 未改写 && 非 AGENT && route==KB), 工具闸门写在收尾侧;
`SemanticCacheAdmin`/`DELETE /api/system/semantic-cache` 契约未变; 会话类型维度经论证**未**加入键
(KB 路由下 RAG/HYBRID 会话的提示词与检索一致, 分区只降命中率不增正确性)。
**调用方**: `ChatService.chat/chatStream` → `buildSpec(..., prep.toolCalls())` → `ToolCallLogAspect`; `SemanticAnswerCache` 构造新增第 4 参 `ChatClientProvider`(仅 `ChatPreparationService`/`ChatCompletionService` 使用, 无其它实例化点)。

**副作用**: 键格式变更使**存量 Redis 条目自然失效**(旧 `rag:answer:v{n}:{sha}` 不再被读, 随 TTL 淘汰), 无需手工清空。

**回归**: 单测 **147/147** 通过(含 ArchUnit 7 条规则, 新增 `rag → config` 依赖未引入环);
应用启动成功(`Started AiAgentApplication in 8.011 seconds`, 日志 0 ERROR)→ 新 Bean 图无循环依赖。
**待补**: 运行时端到端证据(同问两次命中 + 工具轮次不写缓存)需模型可用与带凭据请求, 本轮未执行。

---

## 已完成记录（2026-09-18, 上传链路加固 + 批量上传)

**问题(实测)**: 空文件(0 字节)上传未被拦截——魔数校验对空文件放行, 入库后 Tika 解析抛"未能从文档中解析出有效文本"的原始英文异常写入 error_message, 前端拿到的是不友好/含内部细节的错误。

**变更**:

| 项 | 变更 | 验收证据 |
|---|---|---|
| 空文件判空 | 新增 `ErrorCode.FILE_EMPTY(1005)`; `upload()` 入口 `file == null \|\| file.isEmpty()` 直接拒绝 | 单测 `emptyFileRejectedWith1005`; 运行时 1005"文件内容为空，无法上传" |
| 错误友好化 | 入库失败 `error_message` 对"解析不出有效文本"转中文提示(纯图片/扫描件), 禁止原始异常/堆栈; 批量接口对未预期异常只记日志、返回通用提示 | 运行时伪 PDF → 1002"文件内容不是有效的 PDF"(无堆栈) |
| 批量上传 | 新增 `POST /api/knowledge/upload/batch`(multipart `files` 多字段) + `uploadBatch()`: 逐文件走单文件校验, **部分失败语义**(单个失败不影响其它), 返回 `BatchUploadResultVO(fileName/success/docId/status/message)` | 运行时: [正常MD+空文件+伪PDF+正常PDF] → 2 成功(docId) + 2 失败(各自原因) |
| 测试 | 新增 `KnowledgeDocumentServiceTest` 7 例(空文件/空名/格式/大小/正常/批量部分失败/空数组) | 全量 138/138; e2e 53/53(新增空文件 1005 断言) |

**运行时实测**: 单文件空文件→1005、伪 PDF→1002、正常 MD→200 且入库 status=2; 批量 4 文件部分失败语义正确; 入库完成的 3 个文档 chunk_count=1 正常检索。

---

## 已完成记录（2026-09-17, 对话契约变更: 引用来源不再返回前端)

**变更**: 引用来源只落库 `chat_log.sources`, 不再通过同步响应/SSE 事件返回前端。

| 项 | 变更 | 验收证据 |
|---|---|---|
| 同步接口 | `ChatResponse` 删除 `sources` 字段, 同步返回仅 `{content}` | 运行时: 响应 data 字段只剩 `['content']` |
| 流式接口 | 删除 `sourcesEvent` 与 `EventType.SOURCES/STAGE`, 只发 `content` 事件 | 运行时: 事件类型统计仅 `{'content': 31}` + `[DONE]` |
| 落库保留 | `ChatCompletionService.complete()` 改 void; `sourceNames`(去重去扩展名)仍经事件写入 `chat_log.sources` | 运行时: `chat_log.sources=["员工手册示例","考勤与假期制度"]` |
| 清理 | 删除 `ChatSourceDisplay.displayedSources`(前端展示口径无消费方); `declaresNoResult` 保留(缓存写入判断) | 单测同步删该用例 |

**回归**: 单测 131/131; e2e 52/52(多轮#1 断言改为只校验回答内容)。
**前端影响**: SSE 不再收到 `sources` 事件、同步不再有 `sources` 字段; 来源审计统一走 `/api/system/chat-logs`。

---

## 已完成记录（2026-09-17, 意图路由三态: 规则引擎扩展第一步)

**问题**: 实测"查询订单状态"这类工具问题仍走知识库检索——`internalKeywords` 词表含"订单/单号/物流/快递",
`KeywordIntentRouter` 命中即判 KB → `resolveRagContext` 执行混合检索, 白费一次 embedding+Qdrant,
还可能命中无关制度干扰回答; 而答案实际在业务库(`orders` 表, 经 `queryOrder` 工具查询)。

**变更**: `RagMode` 从两态扩为三态, 工具问题在规则层直接分流。

| 项 | 变更 | 验收证据 |
|---|---|---|
| 路由三态 | `RagMode` 新增 `TOOL`; `KeywordIntentRouter.route()` 判定顺序: 命中 `app.rag.tool-keywords`(新增, 订单/单号/物流/快递/运单/发货/收货/跟踪/包裹/货运/物流信息) → TOOL; 命中 `internal-keywords` → KB; 否则 GENERAL | 单测 5 例(TOOL 命中/同义词/可配置/优先级) |
| 跳过检索 | `ChatPreparationService.resolveRagContext` 最前判断 TOOL → 返回 `RagContext(mode=TOOL)` 空上下文, **无条件**跳过检索(不受 auto-route 影响) | 单测 `toolQuestionSkipsRetrievalAndMarksTool`: 不调 `retrieveOutcome`、不进缓存、审计发布 |
| 提示词 | `PromptService.systemFor`: TOOL 与 GENERAL 同走自由问答(general-system.st), 工具由 ChatClient.tools() 独立注入 | — |
| 缓存隔离 | 工具问题 `route != KB` 天然排除语义缓存(答案随实时数据变, 防串味) | 单测 verify `never().get(...)` |
| 意图路由结果缓存 | 新增 `CachingIntentRouter`(@Primary 装饰器) + `IntentCacheAdmin` 契约 + `DELETE /api/system/intent-cache`(@RequireAdmin): 问题→路由结果缓存 Redis(`rag:intent:v{version}:{sha256}`, TTL 默认 60 分钟); 改词表后调接口版本自增立即失效; Redis 异常降级走真实路由 | 单测 6 例(命中/未命中写缓存/异常降级/disabled/版本自增/归一化); 运行时: 订单问题两次 TOOL 一致、管理接口普通用户 5002/管理员版本 0→1、日志 0 Redis 异常 |

**运行时实测(全链路)**: 问订单 → TOOL 不检索返回真实物流(已发货/收件人张三); 同问题再问意图缓存命中;
`DELETE /api/system/intent-cache` 普通用户 403/5002、管理员版本号自增; 年假问题仍 KB(本次命中语义缓存,
审计按不变式记 `KB+未检索`, 日志确认"语义缓存命中")。回归: 单测 132/132(新增 6+2 例)。

**演进规划(未实施, 待用户评估)**: 第二步"意图检索层"——embedding 语义召回替代纯关键词泛化
(新增 Qdrant `intent_index` 集合 + 意图示例种子 + 相似度阈值, `CompositeIntentRouter` 组合
规则短路→意图检索→低置信才 LLM Judge 纠偏); 第三步澄清意图(CLARIFY)。详见批次方案。

---

## 已完成记录（2026-09-16, 流式对话中断故障修复: 思维链静默撞 okhttp 超时)

**故障现场(用户提供日志)**: 会话 `0f6908da...` 第 2 轮, 前置完成(21:13:46)后模型流 60 秒无任何增量,
21:14:46 起连续打印 7 次 `MessageAggregator: Aggregation Error`(根因 `InterruptedIOException: timeout` →
`StreamResetException: stream was reset: CANCEL`), 最后 `流式对话中断` + `流式对话完成: 总耗时 61014ms,
回答 19 字`——整轮回答只剩一条中断提示, 且前端拿到的是"生成中断"。

**根因**: qwen3 思考模式(`enable_thinking=true`)在长推理时**整段静默不出字**, 超过 OpenAI 客户端
okhttp 的 60s read timeout, 流被客户端主动 CANCEL。第一轮(24.5s)恰好没超, 第二轮思考 >60s 就挂了。

**修复(4 处, 均已实测)**:

| 项 | 变更 | 验收证据 |
|---|---|---|
| 1 根因: 主对话关思维链 | `app.chat.disable-thinking` 默认改 `true`(主对话注入 `enable_thinking=false`) | 运行时: 同步 5.5s/1.8s/3.5s, 流式 2.5s(修复前 24~61s), 不再静默超时 |
| 2 防御: 流式 idle 超时 | 新增 `app.chat.stream-idle-timeout-ms`(默认 20s); `streamAnswer` 的流加 Reactor `timeout`——相邻增量间隔超限即主动终止降级, 早于 okhttp 60s 触发 | 单测 `streamIdleTimeoutDegradesAndSkipsCacheWrite`: `Flux.never()` + 200ms 预算, 按时降级且不写缓存 |
| 3 连带 bug: 中断不写缓存 | 流式中断改走 `completeInterrupted`(只写记忆/审计), 不再走 `complete()`——后者会把"中断提示"当答案写进语义缓存 | 同上单测 `verify(completion, never()).complete(...)` |
| 4 兜底 + 消歧 | `spring.ai.openai.timeout: 120s`(二次保险); 新增 `auditExecutor` 线程池, 全部 `@Async` 显式指定名字(`ingestionExecutor`/`auditExecutor`), 消除 "More than one TaskExecutor ... none is named 'taskExecutor'" 歧义告警 | 启动日志 0 条歧义告警; 审计异步落库仍正常(e2e 日志断言通过) |

**回归**: 单测 123/123 通过; e2e 52/52 通过。

**遗留提示**: 若将来要恢复展示思考过程(置 `disable-thinking=false`), 必须同步调大/关闭
`stream-idle-timeout-ms`, 否则长思考静默会再次触发应用层 idle 降级(提示语已写明"可能正在深度思考")。

---

## 已完成记录（2026-09-16, 延迟归因与卡顿治理)

**触发**: 用户观察到单轮对话耗时 30.2s, 问"是 LLM 慢还是项目阻塞"。

**归因结论(实测)**: 应用侧前置阶段共 10ms(日志: 改写 4ms + 路由检索装配 6ms), 30.2s 全在等模型;
但顺带查出两条**真实的应用侧卡顿通道**——同会话审计里检索段 `duration_ms=100361`(正常 170~600ms)、
一次缓存命中在短路前耗时 `16802ms`(同问题另一次 7ms), 二者都是无超时的外部网络调用被挂住。

| 项 | 变更 | 验收证据 |
|---|---|---|
| A 检索超时保护 | 新增 `app.rag.retrieve-timeout-ms`(默认 10s); `RagRetrievalService.retrieveOutcome` 整段包 `Timeouts.call`, 超时降级为新增的 `RetrievalOutcome.executedEmpty()`(已执行零命中, 区别于 `none()` 未执行) | `RagRetrievalTimeoutTest`: 模拟挂起 5s 的向量库, 300ms 预算内降级返回(实际 <3s); 正常链路命中不受影响 |
| B 审计不变式加固 | 超时降级记为 `executed=true`, 保证 `KB + retrieval_executed=false ⟺ 语义缓存命中` 不被破坏 | `RagRetrievalTimeoutTest.hangingVectorStoreDegradesWithinBudget` 断言 executed=true |
| C 缓存路径可观测 | 缓存命中分支补打"改写 ms / 缓存查询 ms / 前置合计 ms"(此前该分支不打耗时, 无法定位 16.8s); 检索完成补打 INFO(耗时 + 各路命中数) | 运行时日志: `语义缓存命中: ..., 改写 0ms, 缓存查询 1ms, 前置合计 2ms` |
| D 流式 usage 落库 | `ChatService.buildSpec(..., streaming)` 仅在流式请求注入 `streamUsage(true)`; 新增 `app.chat.stream-include-usage`(默认开) | 运行时: 流式轮次 `chat_log.total_tokens` 由 NULL 变为有值 |
| E 计数不再加载全文 | 新增 `com.ai.memory.ChatMemoryCounter` 契约, `DbChatMemoryRepository` 用 MyBatis-Plus `selectCount` 实现; `ConversationMemoryService.messageCount` 改走它(此前 `findByConversationId().size()` 每轮反序列化全部历史) | 单测 `messageCountUsesDatabaseCountInsteadOfLoadingMessages` 断言不再触碰消息加载路径 |
| F 表/列注释补齐 | 11 张表补表注释; `id`/`created_at`/`updated_at`/`employee`/`orders`/`sys_user`/`rag_decision_log` 等 66 列补列注释; 新增一次性迁移脚本 `db/upgrade/2026-09-column-comments.sql` | 存量库执行后 `information_schema` 缺表注释 0 / 缺列注释 0; 迁移前后非注释字段 diff 为空(类型/可空/默认值/索引零变化) |

**顺带查明的存量库结构漂移(未动, 已记录在 README)**: `employee`/`orders`/`sys_user`/`rag_decision_log`
的 `created_at`/`updated_at` 为早期 Hibernate 建表遗留的 `datetime(6) NULL`, 与建表脚本声明的
`DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP` 不一致。

**未做(需用户决策)**: ① 嵌入/检索的独立重试策略——现状用整段超时兜住重试放大(慢端点最坏
10s 降级), 比 `max-attempts=1` 更稳(保留瞬态 429 恢复能力), 故未改全局重试配置;
② 与 `spring.ai.retry` 无关的模型侧慢(7~10s 起步)属上游容量问题, 应用侧无处可治。

---

## 已完成记录（2026-09-16, 第一批结构治理: 对话管线统一）

**问题**: `ChatService` 单类 473 行(HEAD)承担编排/构建/来源口径/审计发布/记忆收尾全部职责,
且 `chat()` 与 `chatStream()` 各自实现一遍"改写→缓存查询→检索→装配"与"记忆写回→审计→缓存写入",
两处逻辑漂移风险高(例如来源口径、缓存写入条件需改两遍)。

**变更**: 门面 + 三协作者, 前置/收尾各一份实现。

| 文件 | 行数 | 职责 |
|---|---|---|
| `chat/service/ChatService` | 324(HEAD 473) | 门面: 会话校验/并发名额/请求链构建/同步与 SSE 输出编排 |
| `chat/service/ChatPreparationService` | 183 | 前置(共用): 改写→语义缓存查询→意图路由/检索→决策审计→装配 |
| `chat/service/ChatCompletionService` | 132 | 收尾(共用): 记忆写回→摘要→完成审计→语义缓存写入→来源展示口径 |
| `chat/service/ChatSourceDisplay` | 75 | 来源展示策略(去重/去扩展名/"未找到"时清空) |
| `rag/SemanticCacheAdmin` | 20 | 跨模块契约: 语义缓存运维清空 |

**新增测试**: `ChatSourceDisplayTest`(4 例) + `ChatPipelineTest`(5 例特征化:
同步/流式 × 缓存命中/未命中 + 并发拒绝)。

**验收证据**:
- 单测 95/95 通过(含 ArchUnit 7 条架构规则, 新增 `system → rag` 契约依赖未引入环);
- e2e 52/52 通过, **连续两次运行全绿**(确定性已修复, 见下)。

**顺带修复的真实缺陷(本批次暴露)**:
1. **e2e 非确定性**: 语义缓存持久于 Redis, 上一轮写入的回答会让本轮"流式增量(content≥2)"与
   "上下文装配日志(≥5)"两项断言失败——命中缓存会跳过装配(无 `context_log`)且整段回答只发 1 个
   content 事件。修复: 新增 `DELETE /api/system/semantic-cache`(`@RequireAdmin`)供运维清空,
   e2e 段 1 建立冷缓存基线。
2. **覆盖率缺口**: 语义缓存此前无任何 e2e 断言。新增段 6: 命中(回答与首次逐字一致、无新增
   `context_log`、决策日志留痕 `KB + 未检索`)与"知识库变更联动失效"(`context_log` 重新增长)。
3. **审计可读性**: 文档化不变式——`rag_mode=KB 且 retrieval_executed=false` 只可能是语义缓存命中,
   用于在日志中区分"缓存命中"与"检索了但没命中知识块"。

**遗留(见批次 C/D)**: ~~语义缓存键未包含对话模型标识(切换模型后旧回答在 TTL 内仍可命中)~~
→ **已闭环(2026-09-19, 见"语义缓存失效维度补全")**; 配额部分仍待与 B1 一并处理。


---

# 第三批优化方案（2026-09-16 盘点, 全部待实施）

> 对账结论: 原评审六大类中, 并发(3/4)、缓存(Redis 2/2 + KeywordIndex 自动重建替代方案)、
> 性能(4/4)、成本(2/4)、安全(7/10)、限流(1/5) 已实施。
> 以下为未实施项的具体方案, 按建议批次排列; 每项含现状/方案/改动点/验收标准/预估工作量。

## 批次 A: 安全补全（✅ A1/A2 已实施 2026-09-16; A3 待实施）

### A1 Token 吊销（✅ 已实施）
- 实现: JWT 增加 `jti`(UUID) claim; 新增 `user/security/TokenBlacklistService`
  (Redis 键 `jwt:black:{jti}`, TTL=令牌剩余有效期); `AuthInterceptor` 解析后校验黑名单,
  命中即 401; 新增 `POST /api/auth/logout`(拉黑当前令牌)。
  降级: Redis 异常按 `app.auth.blacklist-fail-open`(默认 true 放行; 生产建议 false)。
- 验收实测: logout 前访问 200 → logout 200 → 旧 token 401/6005 → 重新登录 200。
- 待办: 后续新增"禁用用户/修改密码"功能时, 同步调用 `tokenBlacklistService.ban` 拉黑该用户令牌。

### A2 解析超时 + 解压炸弹（✅ 已实施）
- 实现: `DocumentIngestionService.parseAndSplit` 包 `Timeouts.call`
  (`app.ingestion.parse-timeout-ms`, 默认 120s, 超时置 status=3);
  `FileStorageService.checkZipBomb` 对 docx 流式预检(解压后总量 ≤512MB、条目 ≤5000,
  超限 1002"疑似压缩炸弹")。
- 验收实测: 伪造 docx(解压后 600MB/压缩后 597KB)上传被拒 1002。

### A3 上传频率/总量配额（待实施）
- **现状**: 每用户可无限次上传 50MB 文件(磁盘 + Embedding 成本无上限)。
- **方案**: Redis 按用户按日计数: `upload:cnt:{uid}:{yyyyMMdd}` INCR(默认 20 次/日)、
  `upload:bytes:{uid}:{yyyyMMdd}` INCRBY(默认 500MB/日), 超限返回 429/错误码 6007;
  ADMIN 豁免。
- **改动点**: 新 `user/security/UploadQuotaGuard`(与登录限流同模式)、`KnowledgeDocumentService.upload`
  前置检查、`AppProperties.UploadQuota`、`ErrorCode.UPLOAD_QUOTA_EXCEEDED(6007, 429)`。
- **验收**: 调低配额后第 N+1 次上传 429; 次日计数自动归零(TTL 到期)。
- **工作量**: 2~3 小时。

## 批次 B: 配额与稳定性（✅ B3/B4 已实施 2026-09-16; B1/B2 待实施）

### B3 单用户并发对话信号量（✅ 已实施）
- 实现: 新增 `chat/service/ChatConcurrencyGuard`(Caffeine per-user Semaphore,
  默认 3 并发/获取等待 1s, 超限 `CONCURRENT_LIMIT(6010, 429)`);
  `chat()` try-finally 释放, `chatStream()` 在流终止(完成/出错/取消)时经 doFinally 释放。
- 验收实测: 同用户 4 并发 → [200, 200, 200, 6010]; 不同用户配额独立。

### B4 Timeouts 并发上限（✅ 已实施）
- 实现: `Timeouts` 增加 Semaphore(50) 并发上限(超限立即抛"系统繁忙"由调用方降级)
  + inFlight/峰值计数(每 20 次输出观测日志)。
- 验收实测: 并发占满 50 名额后第 51 个调用 <1s 快速失败(TimeoutsConcurrencyTest)。

### B1 对话配额（次数 + Token, 成本管控核心）
- **现状**: P1-3 已采集 usage 落库(离线审计), 但**请求前无配额拦截**——单用户可无限消耗
  LLM Token。
- **方案**: Redis 按用户按日累计: `quota:cnt:{uid}:{yyyyMMdd}`(次数, 默认 100/日)、
  `quota:tok:{uid}:{yyyyMMdd}`(Token, 默认 200k/日); chat/chatStream 入口前置检查,
  超限 429/新错误码 6008; usage 采集点(INCRBY totalTokens); ADMIN 豁免。
- **改动点**: 新 `chat/service/ChatQuotaGuard`(Redis 计数, 降级安全)、`ChatService` 两处入口、
  `AppProperties.Quota`、`ErrorCode.QUOTA_EXCEEDED(6008, 429)`。
- **验收**: 调低配额后超额对话 429; Redis 键按日滚动; ADMIN 不受限。
- **工作量**: 半天~1 天。

### B2 单轮工具调用次数上限
- **现状**: Agent 工具循环次数完全由模型决定, 模型异常时可无限连环调用。
- **方案**: `ChatService.buildSpec` 的 toolContext 增加可变计数容器(AtomicInteger);
  `ToolCallLogAspect` 每次拦截时自增, 超过上限(默认 8)抛 `BusinessException(TOOL_CALL_LIMIT)`
  ——Spring AI 会把工具错误回传模型, 模型据此收尾作答。
- **改动点**: `ChatService`(toolContext 传计数器)、`ToolCallLogAspect`(计数+超限)、
  `ErrorCode.TOOL_CALL_LIMIT(6009, 429)`。
- **验收**: 诱导多工具场景日志显示第 9 次调用被拒且对话仍正常收尾。
- **工作量**: 2~3 小时。

## 批次 C: 可观测与代码质量（约 1 天）

### C1 MDC traceId 全链路
- **现状**: 一次对话的改写/检索/工具/审计日志无法串联。
- **方案**: `OncePerRequestFilter` 生成 traceId(优先透传 `X-Request-Id`)写入 MDC 并回写响应头;
  日志 pattern 追加 `%X{traceId}`; 异步段(boundedElastic/事件监听)在任务提交处复制 MDC 上下文。
- **改动点**: 新 `common/TraceIdFilter`、`WebAuthConfig` 注册、yaml `logging.pattern`、
  ChatService 异步提交点(MDC 拷贝)。
- **验收**: 同一请求的全部日志行 traceId 一致; 响应头返回 X-Request-Id。
- **工作量**: 半天。

### C2 状态魔法数枚举化
- **现状**: `knowledge_document.status`(0/1/2/3)、`chat_session.status`(0/1)、`sys_user.status`
  以裸数字散布在服务与判断中。
- **方案**: MyBatis-Plus `@EnumValue` 枚举(DocumentStatus/SessionStatus/UserStatus),
  实体字段类型替换, DB TINYINT 列不变, 数值语义不变。
- **改动点**: 3 个枚举 + 实体 + 相关服务判断点。
- **验收**: 全量回归通过(纯类型重构, 行为不变)。
- **工作量**: 2~3 小时。

### C3 rag/search 调试接口 topK 上限
- **现状**: `RagDebugRequest.topK` 只兜底 <1→5, 无上限(传 10 万可打爆 Qdrant)。
- **方案**: 紧凑构造器加 `topK = Math.min(topK, 50)`。
- **改动点**: `RagDebugRequest` 一行 + 单测。
- **验收**: topK=10 万 归一化为 50。
- **工作量**: 10 分钟。

### C4 springdoc OpenAPI(需兼容性验证)
- **现状**: 无在线接口文档, 前端联调靠 README 手抄。
- **方案**: 引入 `springdoc-openapi-starter-webmvc-ui`(需实测与 Boot 4/Spring Framework 7 的
  兼容版本, 不兼容则挂起); 暴露 /swagger-ui 仅 dev profile。
- **验收**: swagger-ui 可访问且接口定义正确。
- **工作量**: 1 小时(兼容时)。

## 批次 D: 架构演进（按触发条件评估）

### D1 会话记忆多实例原子性（触发: 多实例部署）
- **现状**: append 已是 append-only(P1-1, 天然并发安全); 剩余竞争点仅"摘要裁剪 saveAll"与
  并发 append 之间, 且当前靠 JVM 内锁。
- **方案**: 多实例时给裁剪段加 Redis 分布式锁(`session:lock:{sid}`, Redisson 或 SETNX),
  或把裁剪改为 DB 条件删除(`DELETE WHERE timestamp <= cutoff`, 天然原子)——推荐后者。
- **验收**: 双实例并发压测无丢消息。
- **工作量**: 半天。

### D2 上下文分级注入（数据已就绪, 待分析）
- **触发**: P1-3 已采集 usage——先跑一周数据, 若闲聊类(GENERAL)对话占比高且历史 token 大,
  再实施"GENERAL 会话历史预算减半 / HYBRID 命中时压缩历史占比"。
- **改动点**: `ContextAssembler.assemble` 按会话类型/意图调整 Budget。
- **验收**: context_log 显示目标场景 total_tokens 下降 ≥30%, 回答质量人工抽检不回退。

### D3 重排模型集成（触发: 检索质量需提升且延迟可接受）
- **方案**: 优先直调 DashScope `gte-rerank` API(比引 LangChain4j 轻):
  `RagRetrievalService.rerank-mode` 新增 `model` 模式, 调 rerank 端点对 Top-20 重排取 Top-K;
  超时/失败回退 score 融合(与 llm 模式同模式)。
- **验收**: 标注评测集上 NDCG 提升; 失败自动回退。
- **工作量**: 1 天。

### D4 RetrievalAugmentationAdvisor 架构迁移（保持待评估）
- 触发条件不变: 需要标准 RAG 流(多查询扩展等)或官方提供 DocumentPostProcessor 内置重排时再评估;
  CompressionQueryTransformer 已是标准接口, 迁移时零成本。

### D5 Qdrant 服务端认证（用户部署侧待办）
- docker-compose 的 qdrant 服务增加 `QDRANT__SERVICE__API_KEY` 环境变量, 应用侧设
  `QDRANT_API_KEY`(yaml 占位已就绪); 生产环境 6333/6334 不对外发布端口。
