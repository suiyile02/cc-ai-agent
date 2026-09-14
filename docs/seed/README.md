# 测试数据种子说明

> ⚠️ 本目录内容（测试账号 tester/tester2、演示口令、种子数据）**仅适用于本地演示环境**，
> 严禁在任何生产环境执行；种子 SQL 中的账号均为弱口令演示用途。

## 内容清单（当前已写入本地环境）

### MySQL（ai_agent_db）
| 表 | 数据 | 用途 |
|---|---|---|
| sys_user | `admin`(id=1, ADMIN*)、`tester`/`tester2`(test123, USER) | 登录/角色授权测试 |
| chat_session | `seed-admin-1/2/3`(user 1, 其一已归档 status=0)、`seed-tester-1/2`(tester) | 会话列表/归属隔离/归档过滤 |
| chat_log | 7 条（知识库问答/工具问答/闲聊/空命中），挂在 seed 会话上 | 对话日志查询 |
| tool_call_log | 4 条（queryEmployee/queryOrder/getCurrentTime 成功 + 1 条 FAILED），含 session_id/user_id | 工具日志查询与状态过滤 |
| rag_decision_log | 5 条（KB 命中/未命中 + GENERAL 跳过） | 决策日志与 ragMode 过滤 |
| context_log | 3 条（含一次 truncated=TRUE） | 上下文审计查询 |
| employee | 赵六/钱七/孙八/周九/吴十（与既有张三/李四/王五并存） | Agent 工具 queryEmployee |
| orders | SO20260901001~005（待发货/已发货/已完成/已取消） | Agent 工具 queryOrder |

\* 注意：admin 的 role 需为 ADMIN。若历史数据仍为 USER，执行
`db/upgrade/2026-09-authorization.sql` 中的 UPDATE 语句。

### Qdrant（rag_knowledge_base，1024 维 Cosine）
| doc_id | 文件名 | 分块 | 上传人 |
|---|---|---|---|
| 4 | 员工手册示例.md | 3 | admin |
| 5 | 报销制度.md | 1 | admin |
| 7 | 考勤与假期制度.md | 1 | tester |

向量由应用入库管道（真实 Embedding）生成，`/api/ai/rag/search` 语义检索可直接命中。

## 复用 / 重置方式

1. **MySQL**：`docs/seed/seed-mysql.sql` 可重复执行（先删 seed 标记数据再插入）：
   ```bash
   docker exec -i ai-agent-mysql mysql -uroot -p123456 ai_agent_db < docs/seed/seed-mysql.sql
   ```
2. **Qdrant + knowledge_document**：启动应用后调用上传接口（推荐用 python 构造 multipart，
   Windows 下 curl 发中文文件名会 GBK 乱码，见下）：
   ```python
   # 参考本目录 upload 脚本要点: filename 头必须 UTF-8 编码
   # 登录 -> POST /api/knowledge/upload (multipart, file=文档)
   ```
3. **KeywordIndex（进程内 BM25）**：随应用重启清空，重启后需对每个文档调
   `POST /api/knowledge/documents/{id}/reprocess` 重建；语义检索不受影响。

## 已知坑

- **Windows Git Bash 的 curl**：`-d`/`-F` 中的中文按 GBK 传输，会导致注册昵称/文件名乱码
  （响应与库中均乱码）。请用 python http.client 构造 UTF-8 请求。
- **Qdrant payload 的 doc_id 是字符串**：过滤必须用 `"1"` 而非 `1`（应用的删除向量逻辑已据此修复）。

## 会话历史滚动摘要专项测试

`e2e_summary_test.py`：摘要在**每轮对话写回后异步触发**(请求路径零阻塞), 摘要未就绪时装配走窗口历史兜底。
默认阈值 20 条消息(短测试触不到), 以低阈值参数启动后运行：

```bash
java -jar target/ai-agent-0.0.1-SNAPSHOT.jar   --app.context.summary.trigger-messages=4 --app.context.summary.keep-recent-messages=2
python docs/seed/e2e_summary_test.py
```

验证点：后台摘要生成并注入(轮询 context_log.summaryTokens>0) / 摘要化后早期事实仍可召回 /
二次摘要合并既有摘要 / context_log 审计。摘要生成耗时较长的端点可调大
`--app.context.summary.timeout-ms`(后台执行, 不影响对话响应)。
