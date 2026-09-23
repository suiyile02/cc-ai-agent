# 优化路线图（P0 之后待评估项）

> 状态约定：`待评估` = 需触发条件满足后再实施；每项包含「现状 → 方案 → 验收标准」。
> **2026-09-15 更新：P1-1~P1-5、P2-1~P2-4、P3(Redis 限流+会话缓存) 已全部实施并验收通过**, 明细见文末"已完成记录"。
> P0 已完成项（JWT 启动校验 / 登录失败限流 / 连接池）见本文末尾"已完成记录"。
> **2026-09-24: "配置分段重构第三批"(各模块改注入独立 *Props bean)已决定不做**, 理由见文末同名记录——不要重议。
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

### P3-6 检索相关性判据定标（P3-7 的硬前置；触发条件：灌入 30~50 篇真实体量文档）

> **进度(2026-09-21)**：观测前置已落地——语义路改为**以 0 阈值召回 + 本地过滤**，每轮把**过滤前最大分**
> 写入 `rag_decision_log.semantic_max_score`（迁移脚本 `db/upgrade/2026-09-semantic-max-score.sql`，
> 存量库需手动执行一次）；系统日志决策表新增"TopK / 阈值 / 语义最高分"列。
> **本条剩余的定标动作仍待真实语料**——在此之前 P3-7 的行为改造不应开工。
> **补注(2026-09-22)**：短查询扩展已上线（见"检索口径统一 + 短查询扩展"完成记录），它绕开了"短词余弦偏低"这一类问题，但**没有**改变阈值本身——0.45 依旧无分布依据。定标时要把"扩展前/扩展后"两组分数分开统计，混在一起会得出偏高的分布。

- **现状(实测 2026-09-21)**：`app.rag.similarity-threshold=0.45` 无分布依据——AGENTS.md 只记过一条反例
  ("加班调休" 0.565 被 0.6 误杀)。当前全库仅 **16 个向量点 / 约 5KB 文本**(13 篇 264~506 字符, 3 组重复上传),
  在这个规模上测出的任何分数分布都**不可外推**。
- **为什么必须现在记**：P3-7 要把"答/拒"的决策权交给这个阈值，它就从"过滤噪声"升级成产品行为边界，
  错一个数等于批量误拒答。定标前禁止改判据。
- **方案**：真实语料就位后，用 `/api/ai/rag/search` 以低阈值(0.1)批量跑"库内问题/近邻无关问题/闲聊问题"
  三类各 ≥30 条，记录向量路 top-1 分数分布，取"无关类 P95"与"相关类 P5"之间的空隙定阈值；
  无空隙(分布重叠)则说明 embedding 或分块粒度不够，P3-7 暂缓。
- **验收**：给出三类问题的分数分布表与选定阈值；在该阈值下无关类误通过率 <5%、相关类漏通过率 <5%；
  阈值来源与数据写进 AGENTS.md 相似度条目(替换现在那句"实测分数量级偏低")。

### P3-7 意图预判下线与四出口改造（触发条件：P3-6 完成定标 且 `kb-only` 成为默认）

> **进度(2026-09-22)：A 批 + B 批已实施**。A 批=出口层/检索先行/缓存准入迁移/审计 answer_outcome + 迁移脚本/`kb-only`
> 转默认；B 批=意图路由结果缓存整体下线(删 `CachingIntentRouter`/`IntentCacheAdmin`/
> `DELETE /api/system/intent-cache`/`AppProperties.IntentCache`/`app.intent-cache.*` yaml 段/探针降级清单条目,
> Redis 使用点由 5 项减为 4 项)。**剩余**：统计型验收(无关类误答率、库内漏答率)仍待 P3-6 真实语料。
> 实测已证: 全新主题文档**零配置改动**即可被问到(出口 ANSWERED_FROM_KB, 阈值前分 0.641);
> 天气题 BM25 填满 5 段仍正确判 REFUSED_NO_EVIDENCE(分 0.271); 工具轮判 TOOL_DATA 未被误拒。

- **现状**：意图由 `KeywordIntentRouter` 用两张人工词表**事前猜测**——`internal-keywords` 判要不要检索、
  `tool-keywords` 判是否跳过检索。已证实的后果：`售后服务流程规范.pdf` 在库，但"售后/退货/保修"任一说法
  都不在词表 → 判 GENERAL → 检索整段跳过 → 用户看到"知识库中未找到"，而检索调试页能查到(该页不走路由)。
  **知识库内容能否被问到，取决于有没有人记得改一份与文档无关的配置**，静默失效且默认开启。
- **第 0 步实测(2026-09-21, `auto-route=false` + `kb-only=true`, 未改仓库)**：词表外说法"保修期是多久"命中 5 段
  并答对"1 年"；闲聊/写作给出引导式拒答；TOOL 短路仍生效(命中 0 段/5ms)订单答"已发货"；多资料冲突识别保留。
  无关问题多付的检索成本实测 **159~321ms**(非主导项)。
  **同时证伪了一个判据**：`hits.isEmpty()` 不能当"无关"信号——天气与写诗都是 `语义 0 + 关键词 10 → 最终 5 段`,
  BM25 恒填满名额。出口判据必须建在**向量路过阈值与否**上。
- **方案(四出口，全部由检索结果事后判定，不再事前猜测)**：
  1. 向量路过阈值 → 注入资料作答；
  2. 未过阈值 + `kb-only=true` → 友好拒答(措辞同 `ChatSourceDisplay.NO_RESULT_ANSWER`)；
  3. 未过阈值 + `kb-only=false` → 模型自由作答(现 `general-system.st`；本项目**当前无联网工具**，
     "外部作答"即模型参数知识，真正的外部信息见 P3-8)；
  4. **部分命中**(资料只覆盖问题一部分) → 先答可答部分、再指明哪部分未找到。第 0 步实测此出口行为不稳定：
     "保修期"题正确分半答，"退货地址"题整题拒答，同一模板两种结果——需模板加显式指令(不是架构改造)。
- **连带改动(缺一不可，否则会出现静默错误)**：
  ① 语义缓存准入从 `route()==KB` 迁到"向量路过阈值"，否则闲聊回答被写入跨用户共享缓存；
  ② `CachingIntentRouter` 与 `IntentCacheAdmin`/`DELETE /api/system/intent-cache` 下线(它缓存的预判已不存在；
     **✅ 已于 2026-09-22 B 批完成**——连同 `AppProperties.IntentCache`、`app.intent-cache.*`、探针降级清单条目与单测一并删除)；
  ③ `rag_decision_log.rag_mode` 语义迁移：由"事前猜测的类别"改为"事后记录的出口"，建议取值
     `ANSWERED_FROM_KB`/`REFUSED_NO_EVIDENCE`/`ANSWERED_OPEN`/`PARTIAL`；不改列名但必须改注释与 `/api/system/rag-decisions` 说明，
     否则审计口径静默漂移(现有不变式"KB + retrieval_executed=false ⟺ 缓存命中"要同步重述)；
  ④ `tool-keywords` 降级为纯成本短路(省一次必然无用的检索)，**前提是 `kb-only` 已成默认**——
     默认模式下它仍承担"不把无关制度片段塞进工具类问题上下文"的防污染职责；
  ⑤ `internal-keywords` 从正确性路径摘除(保留字段供未来可选短路，或按 P3-7 结论删除并同步 AGENTS.md)。
- **验收**：(a) 用 P3-6 的三类语料集跑端到端，无关类误答率 0、库内类漏答率 <5%；
  (b) 上传一篇全新主题的千字文档、**不改任何配置**，其内容立即可被问到——这条是本批次的核心验收，
  专门用来证伪"又引入了新的人工同步义务"；(c) 四出口各有落库样本且 `rag_decision_log` 可区分；
  (d) 语义缓存中不存在闲聊/工具类回答；(e) 全量单测 + 与第 0 步同口径的回归脚本复跑通过。

### P3-10 融合重排的量纲修复（触发条件：P3-6 定标完成，或出现"答得出但答偏了"的实例）

- **现状(2026-09-22 实测)**: `rerank-mode=score` 下 `ScoreFusionReranker` 对"只有一路命中"的候选直接取该路分——
  BM25 那一路还会再除以本批最大值得到 0~1 的**相对名次**，而语义余弦实际只有 0.4~0.7，两者不可比。
  后果：仅词面命中的块排在真语义命中之前。实测扩展后的"产品"查询 `semanticCount=4`（4 块过阈值），
  注入的 Top-5 却全是 BM25 相对分 1.0/0.87 的词面命中，`semanticScore` 全为 null。
  出口判据不受影响（它只看向量路），受影响的是**注入上下文的组成**。
- **候选方案**：① 单路命中不再直取原分，改为按"该路是否过阈值"给固定档位（过阈值=1.0，未过=0.5，仅词面=0.3）
  再加名次微调；② BM25 不做批内归一，改用饱和函数 `kw/(kw+k1)` 使其成为绝对量；③ 语义路过阈值者优先，
  同组内再按融合分排（最简单，且与出口判据同口径）。
- **验收**：构造"两路都命中且语义分更高"的查询，断言注入顺序里语义过阈值的块排在仅词面命中之前；
  无关问题（天气/写诗）仍不进知识库作答（出口判据不回退）。
- **附带项**：排查 `documentId=null` 的向量点（早期入库或 reprocess 漏写 `doc_id`）——它使两路同一分块
  无法按 `doc_id:chunk_index` 去重，白占 Top-K 名额。

### P3-8 联网搜索工具（"外部作答"出口的真实实现；触发条件：确需实时外部信息 且 部署允许出网）

- 现状：对话只挂 `BusinessTools`(查自家 MySQL)，**无任何联网检索能力**，"走外部知识"目前等于模型参数知识。
- 方案：以 `@Tool` 形式接入搜索 API(与 `BusinessTools` 同级注册，由模型自主决定调用)，不改动对话管线结构；
  答案含实时外部信息的轮次**必须排除语义缓存**——现有 `toolCalls` 计数闸门天然覆盖，无需新增机制。
- 风险与验收：出网合规(哪些数据不得外发)需先有结论；验收含"搜索结果与 KB 资料同时存在时的引用优先级"人工评测，
  以及工具轮次不写缓存的断言不回退。

### P3-9 知识库入库内容去重（设计已定，用户 2026-09-21 暂缓）

- **现状(实测)**：3 组同名文档各占两个 `doc_id`(售后/产品定价/差旅报销)，`topK=5` 时重复内容吃掉 2 个名额；
  也正是这种重复让小语料下 BM25 对任何问题都能凑满 10 条。
- **已确认方案**：判重键=文件内容 SHA-256、**全局范围**；插入点在既有强制校验链的"魔数校验之后、落盘之前"；
  两层防护=应用层 `WHERE file_hash=?` 给友好提示 + 唯一索引兜并发竞态(捕获 `DuplicateKeyException` 翻译成同一错误码，
  否则冒成 5001)；哈希用 `DigestInputStream` 流式计算(单文件上限 50MB，禁止 `getBytes()` 整读内存)。
- **未决一点**：命中重复按"失败"回(复用批量逐文件失败通道，零契约变更) vs 幂等返回已存在记录(需给
  `KnowledgeUploadVO` 加标记字段，属破坏性变更，要同步前端)。
- **存量与回填**：新列 `file_hash` 为 NULL → `WHERE file_hash=?` 不匹配 → **存量不参与判重**；
  异步入库成功时补算 hash(解析本就在读该文件)，故 reprocess 一次即自动补齐。不加启动扫描。
  MySQL 唯一索引允许多行 NULL，故给存量表加索引不会因 14 条 NULL 失败。
- **边界(明确不做)**：内容相似度/语义去重。同一份制度导出成 PDF 与 DOCX 会得到不同 hash 而放行。
- **验收**：同名不同内容不误拒、改名同内容仍判重、批量中重复项不影响其它文件入库、并发同内容双传只有一个成功。

---

## 决定不做（2026-09-24, 配置分段重构"第三批": 各模块改注入独立 *Props bean）

**原计划**: 第 1 批把 `AppProperties` 按段拆到 `config/props/*Props` 后, 第三批拟把散落的
`appProperties.getRag().getXxx()` 改为各模块直接 `@Resource RagProps`, 约 40 生产 + 25 测试文件。

**决定不做的理由(勿重议)**:
1. **痛点已在第 1 批消除**: 当初想做是因为"373 行配置堆一个类里很乱"; 现在改配置只开对应一个 `*Props`
   文件、`AppProperties` 只剩分组, 剩下的"换注入方式"纯属风格收敛, 不解决任何真实问题。
2. **前提已不成立且有反作用**: 第 1 批刻意**不给** `*Props` 加 `@ConfigurationProperties`(它们靠聚合根
   嵌套绑定)。要单独注入就得二选一——A) 给每段补前缀注解 → 与 `app` 前缀**双写同一份配置**, 两个实例值可能
   不同步, 正是本项目最防的"漂移"; B) 拆掉聚合根 → 65 文件机械大改。两条成本都 > 收益。
3. **footprint 比预估更分散**: 实测 40 个生产文件引用 `AppProperties`, 只有 8 个已在用 `import props`
   (第 1 批改类型名带出的), 全量迁移是一次高风险大 PR。

**若将来触发条件变化**(例如出现"某段配置需要独立于 app 前缀复用"或"多 profile 各绑不同子集"), 再评估;
届时优先选 A 之前先确认不会双写。

---

## 已完成记录（2026-09-23, 配置分段重构第 1 批 + rerank api 模式第 2 批）

对应提交 `fe6ba38`(第 1 批) 与 `cf98bb1`(第 2 批)。细节以 AGENTS.md「配置」「向量化与 RAG」两节为准, 此处只登记要点与验证。

**第 1 批 — 默认值单一来源 = Java + 严格绑定**:
- `AppProperties` 各段搬到 `config/props/*Props`(一一对应前缀), 聚合根只做分组, 既有 `getRag()` 等调用点零改动。
- `application.yaml` 从 190 行业务配置瘦到"环境注入项 + 安全基线 + 成本闸门"; 删无实现的 `spring.ai.openai.rerank`。
- `@ConfigurationProperties(ignoreUnknownFields=false)`(Boot 4 默认 true 会静默忽略未知键)。**当场抓到一个真死配置**:
  `app.auth.rate-limit-backend` 无对应字段(`@ConditionalOnProperty` 装配期开关)。天生不该有字段的键走
  `AppProperties.EXEMPT_UNKNOWN_KEYS` 逐条登记 + `AppPropertiesExemptKeysAdvisor` 只放行清单点名的 unbound 记录(非吞异常)。
- 守卫测试 `AppPropertiesBindingGuardTest`: 每个 `app.*` 键须能走到字段否则报孤儿 / 豁免清单不得过期否则报假旋钮 /
  集合名单一来源 + 安全基线生效值。**反向验证过**: 把 `kb-only` 改成 `kb-only-typo` 会让启动与测试双双变红。

**第 2 批 — api 重排模式 + 阈值判定移到重排之后**:
- `DashScopeReranker`(mode=`api`)用 JDK HttpClient 直连 DashScope 文本排序(Spring AI 2.0.1 OpenAI 模块无 rerank 抽象,
  且该接口非 OpenAI 兼容格式); 失败一律 WARN 节流回退 score。`RagProps` 增 base-url/model/api-key/timeout/candidates。
- 阈值过滤从"召回即过滤"改到"重排之后": 让重排看到全量候选(第 6 名那种低余弦分但答得上的段落才有机会进上下文)。
  出口结论不变(`OutcomeResolver` 读未截断的 `semanticMaxScore`); 过滤器读 `DocumentMeta.semanticScore` 而非 `doc.getScore()`。
- 实测(公共端点, 同一 Key): `qwen3.7-text-rerank`/`gte-rerank-v2` 可用, `gte-rerank`(v1) AccessDenied;
  该接口会**间歇性**抛 `InvalidParameter: Required body invalid`(同一条请求体前一秒失败后两次成功)——见 `DashScopeReranker` 类注释。

**验证**: 两批各自 `mvn test` 全绿(253→264, 含 12 条 ArchUnit); 实启确认严格绑定下正常启动、
`--app.auth.rate-limit-backend=memory` 仍能切限流实现、`--app.rag.rerank-mode=api` 正常启动健康 UP。
rerank 完整链路用本地 echo 服务验过(URL/鉴权头/请求体/index 回填/两路分数共存), 未把真实密钥带出本机。

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

## 已完成记录（2026-09-22, E3① 改写熔断并发修复 + B2 单轮工具调用上限 + 补登记检索服务重构)

| 项 | 变更 | 验收证据 |
|---|---|---|
| E3① 改写熔断并发 | `QueryRewriter.consecutiveFailures` 由普通 `int` 改 `AtomicInteger`(并发 `++` 丢计数、熔断可能永不触发)——`incrementAndGet()`/`set(0)` | 全量回归通过(QueryRewriterTest 熔断用例不变) |
| B2 工具调用上限 | 新增 `ErrorCode.TOOL_CALL_LIMIT(6009, 429)` + `app.chat.max-tool-calls-per-turn`(默认 8, 0=关闭); `ToolCallLogAspect` 复用既有 `toolCalls` 计数, 超限**不执行工具**直接抛错(Spring AI 回传模型收尾作答), 落一条 FAILED 审计 | 单测 `exceedsLimitThrowsToolCallLimitAndSkipsExecution`: 上限 2 时第 3 次抛 6009、`proceed()` 未调用、审计 FAILED; 运行时: 一次对话调 2 工具(queryOrder/queryEmployee)均 SUCCESS 回答完整, 正常路径不受限 |
| 补登记重构 | **RagRetrievalService 已重构为 146 行门面**(此前 532 行): 检索/重排/渲染拆为 `HybridRecaller`/`RrfFuser`/`RetrievalCandidate`/`ScoreFusionReranker`/`RrfOrderReranker`/`LlmReranker`/`RerankStrategyFactory`/`RagContextRenderer`/`DocumentMeta`——即"第二批结构治理", 此前未在 roadmap 登记 | agents.md 已登记; 全量 248/248 通过 |

---

## 已完成记录（2026-09-22, 上传接口加管理员限制——收掉 P0 遗留的未设防写入面)

**问题**: 知识库是全公司共享的 RAG 语料，但 `POST /api/knowledge/upload` 与 `/upload/batch` **只有 JWT**
（`KnowledgeController` 上无任何 `@Require*`）——任何登录用户都能写入语料、消耗 Embedding 成本，
并往同事的答案里注入任意内容（含诱导性材料）。删除/重处理早已挂 `@RequireAdmin`，上传是漏的那一半。

**变更**（`KnowledgeDocumentService`）：

| 方法 | 旧 | 新 |
|---|---|---|
| `upload(file,userId)` | 仅登录态 | `@RequireAdmin` + javadoc 写明理由 |
| `uploadBatch(files,userId)` | 仅登录态 | **同样必须自带** `@RequireAdmin` |
| `KnowledgeController` | 类注释未提鉴权 | 类 javadoc 写明"写操作全部挂 Service" |
| `LayeredArchitectureTest` | 11 条规则 | 新增第 12 条：`KnowledgeDocumentService` 内 `upload*/delete*/reprocess*` 公有方法必须标 `@RequireAdmin` |

**为什么两个方法都要标（关键陷阱）**: `uploadBatch` 内部是 `this.upload(...)` **自调用**，
Spring AOP 不拦截自调用——只标 `upload` 时批量入口照样绕过；而且绕过后的 5002 会被批量循环里的
`catch (BusinessException)` 吞成"每个文件失败但 HTTP 200"，前端完全看不出被拒。
注解必须落在**进入循环之前的外层方法**上。

**范围说明**: 规则刻意只覆盖 `KnowledgeDocumentService`。同包 `FileStorageService.delete(String)`
是磁盘工具方法（由已受控的 `deleteDocument` 调用），第一次写成整包规则时被它误报一次——
授权面是业务写操作，不是所有名字带 delete 的方法。

**验证**: `LayeredArchitectureTest` 12/12 通过；运行时另起 9091 实例（**不碰用户自己跑着的 9090**）实测——
普通用户单文件上传 `HTTP 403 code=5002 该操作需要管理员权限`、批量上传同样 403（证明自调用后门已堵）；
临时提权为 ADMIN 后同一账号上传 `HTTP 200`，随后用管理员删除接口清掉全部 4 个验证文档
（含误跑在旧实例上的 3 个），知识库文档数回到 14、`权限验证*` 残留 0，测试账号角色已改回 USER。

**仍未做**: 上传配额（A3，按用户限流）——管理员同样可能被一个大文件拖满解析线程；
以及"上传后默认状态即参与全公司检索"的审核位（如需人工审核再入库，属产品决策）。
文档同步：AGENTS.md（管理动作/上传校验顺序）、flow-map（端点表 8-9、上传流程图、§22 偏差表 1-2 条已销项）。

---

## 已完成记录（2026-09-22, 检索口径统一 + 短查询扩展)

**问题(用户实测报告)**: 对话问"产品"答"知识库中未找到"，检索调试页却查得到、且显示"匹配度 1.00"。
逐层定位后确认**不是检索没执行**（`rag_decision_log` 显示 `retrieval_executed=1`、`final_hits=5`），
而是三处口径不一致叠加：

| # | 现象 | 真实原因(证据) |
|---|---|---|
| 1 | 页面分数看着很高 | `SourceVO.score` 是**融合相对名次**：仅词面命中时取 `kw/maxKw`，榜首恒 1.0（`ScoreFusionReranker.fused`）。真实余弦只有 **0.4097** |
| 2 | 对话判无据 | 出口判据只认向量路过阈值：`semanticCount=0`、`semanticMaxScore 0.4097 < 0.45` → `REFUSED_NO_EVIDENCE`（P3-7 刻意如此，因 BM25 对无关问题也填满 topK） |
| 3 | 页面阈值"怎么调都没用" | 阈值只过滤语义一路，融合后无任何二次过滤（`mergeAndRerank` 只 `limit(topK)`）→ 条数永远由 topK 决定；且 `RagDebugRequest` 把 null 归一成 **0.0**，`debugRetrieve` 里"null 时用配置默认"的分支是死代码，页面默认 0.3 又是第三套值 |

**变更**：

| 位置 | 旧 | 新 |
|---|---|---|
| `RetrievalCandidate.toDocument` | 只挂 `rerank_score` | 追加 `metadata.semantic_score`/`keyword_score`（融合后仍可见原始分） |
| `DocumentMeta` | `similarity`(=score) | 新增 `semanticScore`：取 metadata 原始余弦；纯语义路直出(未融合)时 score 本身即余弦分；仅词面命中返回 null |
| `SourceVO`（对外契约） | 5 字段 | 7 字段：`score`(名次) + `semanticScore`(余弦) + `keywordScore`(BM25) |
| `POST /api/ai/rag/search` | 返回 `List<SourceVO>`，只查不判 | 返回 `RagDebugVO`：走 `ChatPreparationService.debugSearch`（**与对话同一条链**）并给出 `answerOutcome` 出口预测 |
| `RagDebugRequest` | null 阈值→0.0 | null→跟随 `app.rag.similarity-threshold`；新增 `expandShortQuery`(默认 true) |
| `ShortQueryExpander`(新) + `prompts/short-query-expand.st` + `app.context.short-query.*` | 无 | 去空白 <6 字的提问先补全成完整检索句；AGENT 跳过；超时/失败/空/同句回退 |
| `ChatPreparationService.prepare` | 改写→检索 | 改写→**短查询扩展**→检索；扩展成功即置 `rewritten=true` → 该轮不读写语义缓存 |
| 前端 DebugView | 单一"相似度"列 + 默认 0.3 | 三列分数(余弦/BM25/融合名次)、仅词面命中置灰标注、出口预测卡、参数默认"跟随对话"(勾选才覆盖)、滑杆显示后端回传的实际生效值 |

**踩到并纠正的一点（重要）**：扩展提示词第一版写的是"保留原词、补齐缺失成分、不要引入未提到的业务对象"，
模型给出"产品是什么？"——余弦 **0.3503，比不扩展(0.4097)更差**。改成"补出该词最可能指向的 2~3 个具体方面"后
得"产品的功能特性、定价策略和售后服务条款分别有哪些具体规定？"→ 0.5213，出口翻为 `ANSWERED_FROM_KB`。
对照实测（同一 embedding、同一语料，阈值 0.45）：`产品` 0.4097 拒 / `产品是什么？` 0.3503 拒 /
`产品的定价和套餐有哪些？` 0.7014 答 / `报销` 0.5831 答 / `会议室如何预订以及有哪些规则？` 0.7855 答。
**结论：短查询的问题不是"字数少"，是"缺少与文档用词重叠的方面词"；空泛补全只会稀释向量。**

**验证**：全量单测 **246/246**（新增 `ShortQueryExpanderTest` 8 例、`ChatPreparationServiceTest` 3 例
含"扩展轮不得进语义缓存"、`ScoreFusionRerankerTest` 2 例锁住三分数口径）；浏览器实测调试页：
默认参数下"产品"→ 出口预测"知识库作答"、余弦 0.5346/阈值 0.45、置灰的仅词面命中条目、"短查询已扩展"标记；
勾选覆盖阈值到 0.60 后同一问题出口预测翻为"无据拒答"并给出原因文案——阈值终于"看得见效果"。
对话侧实测：新会话问"产品"两次，均返回《产品定价与套餐说明》真实内容（基础版 199 元/年…），
落库 `answer_outcome=ANSWERED_FROM_KB`、`semantic_max_score` 0.5232/0.5076；两次回答措辞不同即证明未命中缓存。

**新暴露的待办（本批未做，登记为 P3-10）**：`rerank-mode=score` 下**仅词面命中会排在真语义命中之前**——
实测扩展后的"产品"查询里 `semanticCount=4`（4 个分块过了余弦阈值），但 Top-5 注入条目**全部**是 BM25 相对分
1.0/0.87 的词面命中，5 条里 `semanticScore` 全为 null。也就是说"能不能答"现在对了，"注入的是不是最相关那几段"仍不可信。
根因是 `ScoreFusionReranker` 对单路命中直接取该路分（BM25 归一后量纲 0~1，语义余弦只有 0.4~0.7），两路不可比。
另附带发现：部分向量点 payload 缺 `doc_id`（调试响应里 `documentId=null` 的行），导致两路同一分块无法按
`doc_id:chunk_index` 合并去重、白占名额——需排查是早期入库版本还是 reprocess 路径漏写。

---

## 已完成记录（2026-09-22, P3-7 B 批: 意图路由结果缓存整体下线)

**问题**: A 批把"该不该检索/依据什么作答"交给检索结果后, `CachingIntentRouter` 缓存的预判只剩
"是否工具轮"一项成本短路; 而被装饰的 `KeywordIntentRouter` 是对 69 个人工关键词做内存 `contains`
扫描(微秒级、确定性、词表运行期不变)。缓存它 = 每次判定多付两次 Redis 往返(版本号 GET + 结果 GET),
**净负收益**; 且版本号键跨重启存活, 改词表忘记调失效接口时旧结论会在 TTL 内继续生效(静默)。

**变更**: 全部删除, 不留兼容层。

| 位置 | 旧 | 新 | 性质 |
|---|---|---|---|
| `rag/service/CachingIntentRouter.java` | @Primary 装饰器 | 不存在 | 删除 |
| `rag/IntentCacheAdmin.java` | 跨模块清空契约 | 不存在 | 删除 |
| `SystemController.clearIntentCache` | `DELETE /api/system/intent-cache`(@RequireAdmin) | 端点移除, 字段/import 一并删 | 删除 |
| `AppProperties.IntentCache` + 字段 | `app.intent-cache.{enabled,ttl-minutes}` | 不存在 | 删除 |
| `application.yaml` | `intent-cache:` 三行块 | 不存在 | 删除 |
| `RedisReadinessProbe` | 自检日志含"意图缓存=", 降级清单含一项 | 两处移除 | 收敛 |
| `rag/IntentRouter.java`/`KeywordIntentRouter.java` | javadoc 称 GENERAL"跳过检索" | 改为"KB/GENERAL 一律检索, 只剩 TOOL 影响链路" | 纠正(A 批遗漏) |
| `ChatPreparationService.resolveRagContext` | 形参 `boolean toolTurn`, 检索轮硬记 `RagMode.KB` | 形参 `RagMode route`, 审计 `rag_mode` 如实记录预判 | 恢复可观测性 |
| `ChatPreparationService.publishDecision` | `retrievalExecuted = rag.mode()==KB && executed` | `retrievalExecuted = outcome.executed()` | **修 A 批遗漏 bug** |
| `CachingIntentRouterTest` | 6 例 | 不存在 | 删除 |

**运行时发现的 A 批遗漏(本次实测才暴露)**: 闲聊轮(预判 GENERAL)实际执行了检索、日志里 `semantic_max_score=0.2046`
且 `final_hits=5`, 但 `retrieval_executed=false` —— 预判时代遗留的 `rag_mode==KB && executed` 取与把
"真跑过的检索"记成"没跑"。单测此前只断言了 `semanticMaxScore`, 没断言该列, 故漏网; 现补
`generalRouteStillRecordsRetrievalAsExecuted` 钉住。

**为何保留 `rag_mode` 预判留痕**: 前端决策日志的"预判"列与"出口"列并排, 是用来量化词表猜错率的
(P3-6/P3-7 验收数据)。若 `rag_mode` 恒为 KB, 这列即失效——故 B 批顺手把 A 批遗留的硬编码改回真实预判。

**验证**: 全量单测 **232/232**(删 6 例)、`LayeredArchitectureTest` 11 条规则通过; 运行时确认路由不再读写
`rag:intent:*`(存量键无人读取, TTL 内自然过期), 工具轮仍判 `TOOL_DATA`, 闲聊轮仍判 `REFUSED_NO_EVIDENCE`。
Redis 使用点由 5 项减为 4 项, 已同步 `AGENTS.md`、`README.md`、`docs/flow-map.md`(架构图/端点表/键空间/降级总表)、
`docs/method-map.md`(§3/§5/§7)。

---

## 已完成记录（2026-09-19, 降级可观测性: WARN 提级+节流 / Redis 启动自检 / prod 黑名单 fail-closed)

**问题**: Redis 的 5 个使用点(SemanticAnswerCache/SessionCacheService/CachingIntentRouter/
TokenBlacklistService/RedisLoginAttemptLimiter)都有 catch, 但**降级发生了没人知道**——
7 处降级日志是 `log.debug`, 而 `application.yaml:165` 默认 `com.ai: info`, 生产等于完全静默;
运维只能从"缓存突然全不命中"反推。反面问题同时存在: 已 WARN 的那些在 Redis 挂掉时
**每请求每处一条**(一次对话可触发 5 处), 会把日志打爆。此外 prod 无强制口径:
`blacklist-fail-open` 连 yaml 里都没声明, 全靠代码默认 true。

**变更**:

| 项 | 变更 | 验收证据 |
|---|---|---|
| 节流器 | 新增 `common/WarnThrottle`: CAS 抢占放行权, 默认 60s 窗口一条, 下一条汇总"窗口内已抑制 N 条"; 作为字段初始化器接入(`WarnThrottle.of(log)`), **不改任何构造器签名** | `WarnThrottleTest` 3 例(窗口内一条/跨窗口带抑制计数/16 线程并发只一条) |
| 18 处降级日志 | 5 个组件的 catch 全部改 `degraded.warn(...)`, 消息统一为"X 失败(已降级: 具体后果)"; 其中 7 处由 DEBUG 提级 | `grep -c 'log.debug("' 五文件` 后仅剩正常痕迹(如"语义缓存已写入"), 降级路径 0 处 DEBUG |
| 启动自检 | 新增 `config/RedisReadinessProbe`(ApplicationRunner): 探一次 Redis, 可用则打印生效口径, 不可用则逐项列出降级能力; 异常全吸收不外抛 | **运行时实测**: `REDIS_PORT=6399 mvn -o test -Dtest=AiAgentApplicationTests` → `WARN Redis 自检失败(不可用): RedisConnectionFailureException: Unable to connect to Redis —— 以下能力已降级: 登录限流→按未锁定放行(爆破防护暂缺); 语义缓存→按未命中处理(每轮都走检索+模型); 意图路由缓存→按未命中走真实路由; 会话缓存→回退 MySQL 查询; 令牌黑名单→放行(已注销的令牌在过期前仍可用!)`, 且该用例仍 PASS(不影响启动) |
| prod fail-closed | `application.yaml` 显式声明 `rate-limit-backend: redis` 与 `blacklist-fail-open: true`; `application-prod.yaml` 设 false; `SecurityConfigValidator` 在 prod 下配成 true 直接拒绝启动 | `SecurityConfigValidatorTest` 5 例(prod 合规放行/默认密钥拒/短密钥拒/fail-open 拒/非 prod 放行) |

**未改动**: 任何**业务决策路径**(降级返回什么、放行还是拒绝、TTL、键格式)一律未变——本批只动日志与启动校验;
`AuthService`/`AuthInterceptor` 未改; `application-dev.yaml` 未改(dev 保持宽松); `docker-compose.yml` 仍未纳管 Redis(按前一批决定), 改在文档登记。
**调用方**: `WarnThrottle` 被 5 个组件以字段持有; `RedisReadinessProbe` 由 Spring 启动流程调用(无业务调用方);
`SecurityConfigValidator` 新增规则读取 `AppProperties.Auth.blacklistFailOpen`(`application-prod.yaml` 供给)。

**回归**: 单测 **167/167**(155 + 3 + 4 + 5), BUILD SUCCESS; ArchUnit 7 条通过(`user/session/rag` → `common` 依赖未破坏分层)。
**未做**: prod 拒绝启动的**真实 boot** 验证(需注入模拟密钥与 prod profile, 命令被安全策略拦截)——该规则仅有单测覆盖。

---

## 已完成记录（2026-09-19, 登录限流读侧降级 + 进程内条目清理触发点)

**问题**: `RedisLoginAttemptLimiter` 读写降级口径不一致——写侧 `fail()`/`del()` 有 try/catch 放行,
而读侧 `isLocked()`/`remainingLockMs()` 裸调 Redis。Redis 是**默认**限流后端
(`@ConditionalOnProperty(matchIfMissing = true)`), 于是 Redis 不可达时 `AuthService.login:63` 的
`isLocked` 直接抛异常 → `GlobalExceptionHandler:134-139` 兜底 → **HTTP 500 / 5001**,
即"限流存储故障"被放大成"整站无法登录"。顺带发现: `InMemoryLoginAttemptLimiter.evictStale()` 无任何生产调用方
(javadoc 写着"由登录路径顺带触发"却从未接上), 进程内实现的 Map 随用户名/IP 无限增长。

**变更**:

| 项 | 变更 | 验收证据 |
|---|---|---|
| 读侧 fail-open | `isLocked` catch → WARN + `false`; `remainingLockMs` catch → 0; 类 javadoc 写明"读写一致、一律放行、不做可拒绝开关(会把基础设施故障放大成整站不可登录)" | 真实探针(临时用例, 用 Lettuce 连无监听的 127.0.0.1:6399): 裸 `hasKey` 抛 `RedisConnectionFailureException: Unable to connect to Redis`(确为 `DataAccessException` 子类 → 会被兜底成 500), 降级后 `isLocked=false`/`remainingLockMs=0`/`recordFailure` 不抛 |
| 降级契约固化 | 新增 `RedisLoginAttemptLimiterTest` 6 例(Redis down 放行/锁定键仍在→true/TTL 正常读取/写侧吞异常/第 5 次失败写锁定键并删计数的原语义回归) | 单测 6/6 |
| 进程内条目清理 | `recordFailure` 拆出时钟重载, 条目数 > `EVICT_THRESHOLD`(1000) 时顺带 `evictStale(now)`; **门控是刻意的**: 无阈值则每次失败全表扫, 撞库时退化成 O(n²) 自伤 | 单测 `failurePathEvictsStaleEntriesOnceOverThreshold`(越阈后只剩本轮 2 条) + `noEvictionWhileBelowThreshold`(未越阈不清理) |

**未改动**: `LoginAttemptLimiter` 接口签名、`AuthService`(降级在读侧吸收, 调用方零改动)、
`@ConditionalOnProperty` 装配口径、`InMemoryLoginAttemptLimiter` 的窗口/锁定语义、`ErrorCode.LOGIN_LOCKED(6006)`、
`docker-compose.yml`(**本次按用户决定不动**: 本机已有 Redis 占 6379, 纳管会造成端口冲突; 改为在 README §2 与
AGENTS.md「常用命令」显式登记"Redis 需自行启动 + 不可用时全线降级")。
**新增测试私有面**: `recordFailure(String,String,long)` 与 `trackedEntries()` 均标注【仅测试引用】
(内存增长无法从登录行为反推: 过期条目会被滑动窗口重置, 语义无害、只是占内存, 故需条目数观测量)。

**回归**: 单测 **155/155**(147 + 6 + 2), BUILD SUCCESS。
**待补**: 未做"停 Redis → 打登录接口看 6006 而非 500"的运行时端到端(需带凭据请求, 未获授权);
读侧异常路径已由上述真实 Lettuce 探针覆盖。

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

## 批次 B: 配额与稳定性（✅ B3/B4 已实施 2026-09-16; B2 已实施 2026-09-22; B1 待实施）

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

### B2 单轮工具调用次数上限（✅ 已实施 2026-09-22）
- **现状**: Agent 工具循环次数完全由模型决定, 模型异常时可无限连环调用。
- **方案**: `ChatService.buildSpec` 的 toolContext 增加可变计数容器(AtomicInteger);
  `ToolCallLogAspect` 每次拦截时自增, 超过上限(默认 8)抛 `BusinessException(TOOL_CALL_LIMIT)`
  ——Spring AI 会把工具错误回传模型, 模型据此收尾作答。
- **改动点**: `ChatService`(toolContext 传计数器)、`ToolCallLogAspect`(计数+超限)、
  `ErrorCode.TOOL_CALL_LIMIT(6009, 429)`。
  **进度提示(2026-09-19)**: 计数器链路已随"语义缓存工具轮次闸门"落地
  (`PreparedChat.toolCalls` → `buildSpec` 的 `toolContext["toolCalls"]` → `ToolCallLogAspect` 自增),
  本项只剩"超限抛错 + 错误码", 工作量下调约一半。
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

## 批次 E: 多实例正确性与成本防线（2026-09-19 Redis 盘点新增, 全部待实施）

> 触发条件统一为"**要上多实例**"或"成本/正确性开始咬人"。降级与日志口径已由
> "降级可观测性"批次固化(见已完成记录), 以下是**功能语义**层面的缺口, 不是日志问题。
> 每项落地都必须遵守: 读写两侧 catch + `WarnThrottle` WARN, 且 Redis 故障时回退到"当前单实例行为"。

### E1 单用户并发名额多实例统一（触发: 多实例部署; 优先级最高——成本直接被实例数放大）
- **现状**: `chat/service/ChatConcurrencyGuard` 用 Caffeine `Cache<userId, Semaphore>`(进程内),
  N 实例下同一用户实际并发 = `3 × N`, B3 建立的保护被稀释。
- **方案**: Redis 租约式信号量——`concur:{uid}` 有序集, 成员=每次获取的随机句柄, score=过期时间戳;
  获取脚本: `ZREMRANGEBYSCORE 0 now`(回收僵尸) → `ZCARD` < max 才 `ZADD`; 释放=`ZREM`; 全程一段 Lua 保原子。
  实例被 kill 未释放的句柄由租约到期自动回收(优于本地 Semaphore 的泄漏风险)。
- **改动点**: 新增 `chat/service/RedisConcurrencyGuard`(实现现有 `Handle` 契约)、
  `AppProperties.Concurrency.backend: memory|redis`(默认 memory 灰度)、配置 `lease-ms`(默认 5min, 须 > 最长对话)。
- **验收**: 双实例同用户并发 4 → 第 4 个 6010; kill 掉持锁实例后 ≤lease 时间内名额自动恢复;
  Redis 挂 → 回落本地信号量且对话不中断。
- **工作量**: 半天~1 天。

### E2 冷缓存同问并发穿透（single-flight; 触发: 并发用户数上来或缓存刚被整体失效）
- **现状**: `ChatPreparationService:94-98` 查缓存未命中即各自检索+生成——知识库失效后的第一波
  相同问题会重复打 Embedding/Qdrant/模型 N 次(纯浪费 Token, 且是成本审计里最难解释的尖峰)。
- **方案**: 以正缓存键为锁 `lock:sf:{answerKey}` `SET NX PX 120s`; 抢到者生成后 `put`+`DEL`;
  未抢到者以 50ms 间隔轮询缓存 ≤3s, 命中即返回, 超时则自行生成(绝不因等锁而失败)。
- **验收**: 同问题并发 5 → 模型调用 1 次(日志/`chat_log` 计数), 其余 4 个走缓存; Redis 挂 → 5 次照旧。
- **工作量**: 2~3 小时。

### E3 改写熔断状态共享 + 修数据竞争（先做后半段, 它与 Redis 无关）
- **现状**: `context/service/QueryRewriter:44` 的 `private int consecutiveFailures` 是**普通 int**,
  被并发请求线程 `++` → 数据竞争(丢计数, 熔断可能永不触发); 且状态在各 JVM 独立,
  端点整体劣化时实例 A 已冷却、实例 B 仍每请求白等满 `query-rewrite.timeout-ms`(默认 30s)。
- **方案**: ① 立即项: 改 `AtomicInteger`(`incrementAndGet`/`set(0)`), 零风险;
  ② 多实例项: 计数与冷却写 Redis(`breaker:rewrite` INCR + EXPIRE 60s, GET 到即跳过), 异常回退本地。
- **验收**: 并发失败注入下计数不丢(单测); 双实例冷却一致。
- **工作量**: ①10 分钟 ②1~2 小时。

### E4 关键词索引跨实例一致（变更广播, 不是外置）
- **现状**: `rag/service/KeywordIndex` 是进程内 BM25(读写锁保护本地结构)——A 实例入库并注册后
  B 实例不知道, 同一问题落在不同实例命中不同("新文档时灵时不灵")。
- **方案**: 入库/删除/重处理成功后 `PUBLISH kw:index:changed {docId,op}`; 各实例订阅
  (`RedisMessageListenerContainer`)后做**本地**增量注册/移除——读路径零网络开销。
  保留 `auto-rebuild-index` 作冷启动兜底。**明确不要**把倒排整体搬进 Redis(每次检索多一跳网络, 延迟预算不允许)。
- **验收**: 双实例下上传文档后, 两实例的 `rag_decision_log.keywordHits` 同时 >0。
- **工作量**: 半天。

### E5 定时任务与启动重建的跨实例互斥
- **现状**: `system/service/LogCleanupScheduler:38` 的 cron 在每个实例同时触发(重复 DELETE 争锁、放大 binlog)。
- **方案**: 任务入口 `SET lock:cron:log-cleanup <token> NX PX 2h`, 抢不到直接 return; 不引 ShedLock(仅一处收益不足)。
  注: `KeywordIndexRebuilder` **不加此锁**——每实例都必须自建本地索引, 互斥反而漏建。
- **工作量**: 1 小时。

### E6 用户禁用/改密后的即时吊销（补 A1 待办）
- **现状**: 黑名单只能拉黑"当前这一枚 jti"; 禁用用户或改密后, 其**其它**已签发令牌仍有效直到自然过期(最长 7 天)。
- **方案**: 吊销水位线 `user:revoked:{uid}` = 操作时刻秒级时间戳(TTL = `token-expire-hours`),
  `AuthInterceptor` 解析后比较 `iat < revoked` → 401/6005; 禁用/改密/删除用户时 `SET`。
  Redis 挂 → 跳过水位线校验(等于现状)并 WARN 节流。
- **验收**: 禁用用户后其旧 token 立即 401; 水位线删除/过期后不影响新登录。
- **工作量**: 2 小时。

### E7 文档入库任务持久化（当前**重启即丢任务**, 与 Redis 无关但正相关）
- **现状**: 入库投递到内存队列(`AsyncConfig:24-36`, 容量 100, `CallerRunsPolicy`),
  `DocumentIngestionService:56` 置 `status=1` 后异步推进。**进程重启/崩溃 → 队列里的文档永久停在 0/1**
  (界面一直"处理中"、检索不到、无人重试); 全项目仅 `KeywordIndexRebuilder` 挂了 `ApplicationReadyEvent`。
- **方案**: ① 最低成本(先做, 不依赖 Redis): 启动时扫 `status IN (0,1)` 的文档重新投递入库;
  ② 多实例分担: Redis Stream(`XADD ingest:docs` + 消费者组 + `XAUTOCLAIM` 认领超时未确认项, 天然重试),
  状态机 0→1→2/3 不变。
- **验收**: 入库中途 kill 应用 → 重启后该文档自动继续并最终 status=2; 双实例下任务不重复消费。
- **工作量**: ①0.5 小时 ②1 天。

### E8 配额类（沿用既有 B1/A3 方案, 未变）
- 对话次数+Token 配额(B1)与上传配额(A3)按原方案落地即可; 两者与 E1 共用"Redis 挂 → 放行 + WARN 节流"口径。
