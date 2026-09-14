-- =====================================================================
-- 授权改造增量脚本(2026-09): 管理员角色 + 日志表归属用户
-- 适用: 在本版本之前创建的存量数据库(MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,
--       spring.sql.init 无法幂等执行 ALTER, 因此本脚本需手动执行一次)。
-- 新库无需执行: db/schema-mysql-extra.sql 已包含相同列。
-- =====================================================================
ALTER TABLE sys_user        ADD COLUMN role   VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色: ADMIN/USER';
ALTER TABLE sys_user        UPDATE role = 'ADMIN' WHERE username = 'admin';

ALTER TABLE tool_call_log   ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID(授权过滤)';
ALTER TABLE tool_call_log   ADD INDEX idx_user_id (user_id);

ALTER TABLE rag_decision_log ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID(授权过滤)';
ALTER TABLE rag_decision_log ADD INDEX idx_rag_dec_user (user_id);

ALTER TABLE context_log     ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID(授权过滤)';
ALTER TABLE context_log     ADD INDEX idx_ctx_user (user_id);
