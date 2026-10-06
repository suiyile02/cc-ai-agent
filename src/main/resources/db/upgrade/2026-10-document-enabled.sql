-- =============================================================================
-- 一次性迁移: knowledge_document 增加"检索启用开关"列(文档禁用/启用功能)
-- =============================================================================
-- 为什么需要: 禁用功能要求"参与检索与否"与"入库生命周期"(status 0/1/2/3)解耦——
--   禁用不删向量点/不删索引(启用零成本恢复), 因此不能用 status 表达, 需独立的布尔列。
-- 执行方式(不随 spring.sql.init 自动跑, 只对新库生效):
--   mysql -h127.0.0.1 -P3306 -uroot -p ai_agent_db < src/main/resources/db/upgrade/2026-10-document-enabled.sql
-- 幂等性: MySQL 的 ADD COLUMN 无 IF NOT EXISTS, 重复执行会报 1060 Duplicate column, 属预期(执行一次即可)。
-- 列位置用 AFTER 对齐新库建表顺序(create_table.sql), 避免新库与存量库列序不一致。
-- 历史行由 DEFAULT 1 填充: 上线前所有文档均参与检索, 行为不变。

ALTER TABLE knowledge_document
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1
        COMMENT '检索启用开关：1-参与检索(默认) 0-已禁用(仅 status=2 时有意义；禁用不删向量/索引, 启用零成本恢复)'
        AFTER status;
