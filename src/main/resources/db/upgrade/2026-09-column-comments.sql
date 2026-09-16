-- =====================================================================
-- 注释补齐增量脚本(2026-09): 为存量库的 11 张表补齐表注释与缺失的列注释
--
-- 生成方式: 由 information_schema 按存量库**真实列定义**生成, 仅追加 COMMENT——
--   类型/可空/默认值/自增/ON UPDATE 全部照抄, 执行后除注释外无任何 schema 变化。
-- 适用: 在本版本之前创建的存量数据库(MySQL 不支持 MODIFY COLUMN IF NOT EXISTS,
--       spring.sql.init 无法幂等执行 ALTER, 故需手动执行一次; 重复执行亦无害)。
-- 新库无需执行: create_table.sql / schema-mysql-extra.sql 已内联同样的表列注释。
--
-- 注意(存量库与建表脚本的历史差异, 本脚本刻意不动):
--   employee / orders / sys_user / rag_decision_log 的 created_at, updated_at 为
--   datetime(6) NULL 无默认值(早期 Hibernate 建表遗留), 而建表脚本声明为
--   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP。如需对齐请另行评估迁移。
-- =====================================================================

-- ---------- chat_log ----------
ALTER TABLE `chat_log` COMMENT '对话审计日志（问答全文/引用来源/模型/Token/耗时）';
ALTER TABLE `chat_log` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `chat_log` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- chat_session ----------
ALTER TABLE `chat_session` COMMENT '对话会话（归属用户/会话类型/生命周期状态）';
ALTER TABLE `chat_session` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `chat_session` MODIFY COLUMN `status` int NOT NULL COMMENT '0-已归档 1-进行中';
ALTER TABLE `chat_session` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `chat_session` MODIFY COLUMN `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- context_log ----------
ALTER TABLE `context_log` COMMENT '上下文装配审计日志(各段 Token 占用/截断/查询改写)';
ALTER TABLE `context_log` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `context_log` MODIFY COLUMN `session_id` varchar(36) NOT NULL COMMENT '所属会话ID';
ALTER TABLE `context_log` MODIFY COLUMN `system_tokens` int NOT NULL DEFAULT 0 COMMENT 'system 提示词段 Token 数';
ALTER TABLE `context_log` MODIFY COLUMN `history_tokens` int NOT NULL DEFAULT 0 COMMENT '历史消息段 Token 数';
ALTER TABLE `context_log` MODIFY COLUMN `summary_tokens` int NOT NULL DEFAULT 0 COMMENT '历史摘要段 Token 数';
ALTER TABLE `context_log` MODIFY COLUMN `rag_tokens` int NOT NULL DEFAULT 0 COMMENT 'RAG 检索上下文段 Token 数';
ALTER TABLE `context_log` MODIFY COLUMN `user_tokens` int NOT NULL DEFAULT 0 COMMENT '本轮用户输入 Token 数';
ALTER TABLE `context_log` MODIFY COLUMN `total_tokens` int NOT NULL DEFAULT 0 COMMENT '合计 Token 数(各段之和)';
ALTER TABLE `context_log` MODIFY COLUMN `model_max_tokens` int NULL COMMENT '模型上下文窗口上限(Token)';
ALTER TABLE `context_log` MODIFY COLUMN `history_messages` int NOT NULL DEFAULT 0 COMMENT '纳入的历史消息条数';
ALTER TABLE `context_log` MODIFY COLUMN `truncated` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否因 Token 预算不足发生截断';
ALTER TABLE `context_log` MODIFY COLUMN `duration_ms` int NULL COMMENT '本轮问答总耗时(毫秒)';
ALTER TABLE `context_log` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- conversation_summary ----------
ALTER TABLE `conversation_summary` COMMENT '会话历史滚动摘要(超窗老对话压缩存储)';
ALTER TABLE `conversation_summary` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `conversation_summary` MODIFY COLUMN `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- employee ----------
ALTER TABLE `employee` COMMENT '员工演示数据(Agent 工具 queryEmployee 数据源)';
ALTER TABLE `employee` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `employee` MODIFY COLUMN `created_at` datetime(6) NULL COMMENT '创建时间';
ALTER TABLE `employee` MODIFY COLUMN `updated_at` datetime(6) NULL COMMENT '更新时间';
ALTER TABLE `employee` MODIFY COLUMN `department` varchar(64) NULL COMMENT '所属部门';
ALTER TABLE `employee` MODIFY COLUMN `email` varchar(128) NULL COMMENT '电子邮箱';
ALTER TABLE `employee` MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '姓名';
ALTER TABLE `employee` MODIFY COLUMN `phone` varchar(32) NULL COMMENT '联系电话';
ALTER TABLE `employee` MODIFY COLUMN `position` varchar(64) NULL COMMENT '职位';

-- ---------- knowledge_document ----------
ALTER TABLE `knowledge_document` COMMENT '知识库文档（上传记录/异步入库状态/分块数）';
ALTER TABLE `knowledge_document` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `knowledge_document` MODIFY COLUMN `status` int NOT NULL COMMENT '0-待处理 1-处理中 2-已完成 3-失败';
ALTER TABLE `knowledge_document` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `knowledge_document` MODIFY COLUMN `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ---------- orders ----------
ALTER TABLE `orders` COMMENT '订单演示数据(Agent 工具 queryOrder 数据源)';
ALTER TABLE `orders` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `orders` MODIFY COLUMN `created_at` datetime(6) NULL COMMENT '创建时间';
ALTER TABLE `orders` MODIFY COLUMN `updated_at` datetime(6) NULL COMMENT '更新时间';
ALTER TABLE `orders` MODIFY COLUMN `amount` decimal(12,2) NULL COMMENT '订单金额(元)';
ALTER TABLE `orders` MODIFY COLUMN `customer_name` varchar(64) NULL COMMENT '客户名称';
ALTER TABLE `orders` MODIFY COLUMN `express_no` varchar(64) NULL COMMENT '快递单号';
ALTER TABLE `orders` MODIFY COLUMN `order_no` varchar(64) NOT NULL COMMENT '订单号(唯一)';
ALTER TABLE `orders` MODIFY COLUMN `product_name` varchar(128) NULL COMMENT '商品名称';
ALTER TABLE `orders` MODIFY COLUMN `status` varchar(20) NULL COMMENT '订单状态: 已下单/已发货/已签收等';

-- ---------- rag_decision_log ----------
ALTER TABLE `rag_decision_log` COMMENT 'RAG 意图路由与检索决策审计日志';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `created_at` datetime(6) NULL COMMENT '创建时间';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `duration_ms` int NULL COMMENT '路由与检索耗时(毫秒)';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `final_hits` int NULL COMMENT '最终注入上下文的命中段数(重排后 Top-K)';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `keyword_hits` int NULL COMMENT '关键词 BM25 路召回数量';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `rag_mode` varchar(16) NOT NULL COMMENT '意图路由结果: KB/GENERAL';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `rerank_mode` varchar(16) NULL COMMENT '重排模式: score/llm/none';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `retrieval_executed` bit(1) NOT NULL COMMENT '是否真的执行了检索; KB 且为 0 表示语义缓存命中';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `semantic_hits` int NULL COMMENT '语义向量路召回数量';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `session_id` varchar(36) NOT NULL COMMENT '所属会话ID';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `session_type` varchar(20) NOT NULL COMMENT '会话类型: RAG/AGENT/HYBRID';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `similarity_threshold` double NULL COMMENT '本次检索相似度阈值';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `top_k` int NULL COMMENT '本次检索 Top-K 配置值';
ALTER TABLE `rag_decision_log` MODIFY COLUMN `user_message` text NULL COMMENT '用户原始问题(截断500字)';

-- ---------- spring_ai_chat_memory ----------
ALTER TABLE `spring_ai_chat_memory` COMMENT 'Spring AI 会话记忆（对话历史消息, append-only 追加写入）';
ALTER TABLE `spring_ai_chat_memory` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';

-- ---------- sys_user ----------
ALTER TABLE `sys_user` COMMENT '系统用户(注册/登录/角色鉴权)';
ALTER TABLE `sys_user` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `sys_user` MODIFY COLUMN `created_at` datetime(6) NULL COMMENT '创建时间';
ALTER TABLE `sys_user` MODIFY COLUMN `updated_at` datetime(6) NULL COMMENT '更新时间';
ALTER TABLE `sys_user` MODIFY COLUMN `nickname` varchar(64) NULL COMMENT '昵称';
ALTER TABLE `sys_user` MODIFY COLUMN `password` varchar(128) NOT NULL COMMENT '密码(PBKDF2 加盐哈希)';
ALTER TABLE `sys_user` MODIFY COLUMN `status` int NOT NULL COMMENT '状态: 1正常 0禁用';
ALTER TABLE `sys_user` MODIFY COLUMN `username` varchar(64) NOT NULL COMMENT '登录用户名(唯一)';

-- ---------- tool_call_log ----------
ALTER TABLE `tool_call_log` COMMENT 'Agent 工具调用审计日志（入参/出参已 PII 脱敏）';
ALTER TABLE `tool_call_log` MODIFY COLUMN `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID';
ALTER TABLE `tool_call_log` MODIFY COLUMN `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
