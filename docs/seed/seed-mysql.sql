-- =====================================================================
-- 测试数据种子(可重复执行: 先删除标记数据再插入)
-- 范围: 测试用户 / 员工 / 订单 / 会话 / 四张日志表
-- 知识库文档与 Qdrant 向量请走应用入库接口(见 docs/seed/README.md)
-- 复用方式: docker exec -i ai-agent-mysql mysql -uroot -p123456 ai_agent_db < docs/seed/seed-mysql.sql
-- =====================================================================

-- 1. 测试用户(密码均为 test123, 与 admin 同一套哈希参数)
DELETE FROM sys_user WHERE username IN ('tester', 'tester2');
INSERT INTO sys_user (username, password, nickname, role, status) VALUES
('tester',  'pbkdf2$100000$a25798473ff2bc20d0d5bafafc0fe08a$cdda313944e1852742018bc9c4b46ce13de2835e975a0dee5076d5ee475409b5', '测试用户一', 'USER', 1),
('tester2', 'pbkdf2$100000$a25798473ff2bc20d0d5bafafc0fe08a$cdda313944e1852742018bc9c4b46ce13de2835e975a0dee5076d5ee475409b5', '测试用户二', 'USER', 1);

-- 2. 员工(Agent 工具查询用)
DELETE FROM employee WHERE name IN ('赵六', '钱七', '孙八', '周九', '吴十');
INSERT INTO employee (name, department, position, phone, email) VALUES
('赵六', '研发部',     '前端工程师', '13800000006', 'zhaoliu@demo.com'),
('钱七', '研发部',     '测试工程师', '13800000007', 'qianqi@demo.com'),
('孙八', '人力资源部', '招聘专员',   '13800000008', 'sunba@demo.com'),
('周九', '财务部',     '会计',       '13800000009', 'zhoujiu@demo.com'),
('吴十', '销售部',     '客户经理',   '13800000010', 'wushi@demo.com');

-- 3. 订单(覆盖多种状态)
DELETE FROM orders WHERE order_no LIKE 'SO202609%';
INSERT INTO orders (order_no, customer_name, product_name, amount, status, express_no) VALUES
('SO20260901001', '赵六', '企业知识库一体机',   32999.00, '待发货', ''),
('SO20260901002', '钱七', '智能问答年费订阅',   12999.00, '已发货', 'SF9000000001'),
('SO20260901003', '孙八', '向量化存储扩容包',    4999.00, '已完成', 'SF9000000002'),
('SO20260901004', '周九', '模型推理加速卡',     15999.00, '已发货', 'SF9000000003'),
('SO20260901005', '吴十', '文档解析服务包',      2999.00, '已取消', '');

-- 4. 会话(admin 两条进行中 + 一条已归档; tester 两条进行中)
DELETE FROM chat_session WHERE session_id LIKE 'seed-%';
INSERT INTO chat_session (session_id, user_id, title, session_type, status) VALUES
('seed-admin-1',  1, '【测试】年假与考勤咨询', 'HYBRID', 1),
('seed-admin-2',  1, '【测试】报销流程追问',   'HYBRID', 1),
('seed-admin-3',  1, '【测试】已归档会话',     'RAG',    0),
('seed-tester-1', (SELECT id FROM sys_user WHERE username = 'tester'),  '【测试】订单物流查询', 'HYBRID', 1),
('seed-tester-2', (SELECT id FROM sys_user WHERE username = 'tester'),  '【测试】通用闲聊',     'RAG',    1);

-- 5. 对话日志(知识库问答 / 工具问答 / 闲聊, 与知识文档名呼应)
DELETE FROM chat_log WHERE session_id LIKE 'seed-%';
INSERT INTO chat_log (session_id, user_id, user_message, assistant_reply, sources, model_name, total_tokens, duration_ms) VALUES
('seed-admin-1', 1, '入职满两年的员工有多少天年假？',
 '根据员工手册，入职满两年的员工每年享有 10 天带薪年假，需提前 3 个工作日在 OA 系统提交申请。',
 '[{"fileName":"员工手册示例.md","documentId":null,"chunkIndex":0,"snippet":"年假：入职满一年 5 天，满两年 10 天，满十年 15 天……","score":0.83}]',
 'qwen3.7-flash', 1024, 2350),
('seed-admin-1', 1, '那病假需要什么证明？',
 '病假超过 2 天需提供二级及以上医院的诊断证明，并在返岗后 2 个工作日内补交考勤员备案。',
 '[{"fileName":"员工手册示例.md","documentId":null,"chunkIndex":2,"snippet":"病假：2 天以内无需证明……","score":0.79}]',
 'qwen3.7-flash', 896, 1890),
('seed-admin-2', 1, '差旅报销的住宿标准是多少？',
 '一线城市住宿标准为每晚 500 元，其他城市 350 元，超标部分需部门总监审批。',
 '[{"fileName":"报销制度.md","documentId":null,"chunkIndex":1,"snippet":"住宿标准：一线城市 500 元/晚……","score":0.86}]',
 'qwen3.7-flash', 1105, 2560),
('seed-admin-2', 1, '张三在哪个部门？',
 '张三在研发部，担任后端工程师。',
 NULL,
 'qwen3.7-flash', 512, 1680),
('seed-admin-3', 1, '信息安全红线有哪些？',
 '知识库中未找到相关信息。',
 '[]',
 'qwen3.7-flash', 300, 980),
('seed-tester-1', (SELECT id FROM sys_user WHERE username = 'tester'), '帮我查一下订单 SO20260901002 的物流',
 '订单 SO20260901002 已发货，物流单号 SF9000000001，可通过顺丰官网查询。',
 NULL,
 'qwen3.7-flash', 480, 1520),
('seed-tester-2', (SELECT id FROM sys_user WHERE username = 'tester'), '今天天气怎么样？',
 '抱歉，我是企业内部助手，暂时无法查询实时天气。可以帮你查询制度、员工与订单信息。',
 '[]',
 'qwen3.7-flash', 260, 860);

-- 6. 工具调用日志(含 session_id/user_id, 覆盖成功与失败)
DELETE FROM tool_call_log WHERE session_id LIKE 'seed-%';
INSERT INTO tool_call_log (session_id, user_id, tool_name, input_params, output_result, status, duration_ms, error_message) VALUES
('seed-admin-2', 1, 'queryEmployee',
 '{"name":"张三"}',
 '{"name":"张三","department":"研发部","position":"后端工程师","phone":"13800000001","email":"zhangsan@demo.com"}',
 'SUCCESS', 46, NULL),
('seed-tester-1', (SELECT id FROM sys_user WHERE username = 'tester'), 'queryOrder',
 '{"orderNo":"SO20260901002"}',
 '{"orderNo":"SO20260901002","customerName":"钱七","productName":"智能问答年费订阅","amount":12999.00,"status":"已发货","expressNo":"SF9000000001"}',
 'SUCCESS', 38, NULL),
('seed-admin-1', 1, 'getCurrentTime',
 '{}',
 '"2026-09-12 10:30:00"',
 'SUCCESS', 2, NULL),
('seed-admin-2', 1, 'queryEmployee',
 '{"name":"不存在的人"}',
 'null',
 'FAILED', 12, 'Employee not found: 不存在的人');

-- 7. RAG 意图路由决策日志(KB 命中 / KB 未命中 / GENERAL 跳过)
DELETE FROM rag_decision_log WHERE session_id LIKE 'seed-%';
INSERT INTO rag_decision_log (session_id, user_id, user_message, rag_mode, session_type, retrieval_executed, semantic_hits, keyword_hits, final_hits, top_k, similarity_threshold, rerank_mode, duration_ms) VALUES
('seed-admin-1', 1, '入职满两年的员工有多少天年假？', 'KB',      'HYBRID', TRUE,  4, 3, 5, 5, 0.6, 'score', 186),
('seed-admin-1', 1, '那病假需要什么证明？',           'KB',      'HYBRID', TRUE,  3, 2, 4, 5, 0.6, 'score', 142),
('seed-admin-2', 1, '差旅报销的住宿标准是多少？',     'KB',      'HYBRID', TRUE,  5, 4, 5, 5, 0.6, 'score', 205),
('seed-admin-2', 1, '张三在哪个部门？',               'GENERAL', 'HYBRID', FALSE, 0, 0, 0, 5, 0.6, 'score', 3),
('seed-tester-2', (SELECT id FROM sys_user WHERE username = 'tester'), '今天天气怎么样？', 'GENERAL', 'RAG', FALSE, 0, 0, 0, 5, 0.6, 'score', 2);

-- 8. 上下文装配日志(Token 预算审计)
DELETE FROM context_log WHERE session_id LIKE 'seed-%';
INSERT INTO context_log (session_id, user_id, user_message, rewritten_query, intent_mode, system_tokens, history_tokens, summary_tokens, rag_tokens, user_tokens, total_tokens, model_max_tokens, history_messages, truncated, duration_ms) VALUES
('seed-admin-1', 1, '入职满两年的员工有多少天年假？', NULL, 'KB', 220, 360, 0, 780, 24, 1384, 8192, 6, FALSE, 2350),
('seed-admin-1', 1, '那病假需要什么证明？', '病假需要什么证明材料', 'KB', 220, 520, 0, 640, 18, 1398, 8192, 8, FALSE, 1890),
('seed-admin-2', 1, '差旅报销的住宿标准是多少？', NULL, 'KB', 220, 610, 320, 720, 22, 1892, 8192, 10, TRUE, 2560);
