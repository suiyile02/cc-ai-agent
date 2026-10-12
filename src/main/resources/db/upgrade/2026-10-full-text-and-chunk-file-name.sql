-- =============================================================================
-- 一次性迁移: knowledge_document 增加"文档全部内容"列 + knowledge_chunk 增加"所属文档文件名"列
-- =============================================================================
-- 为什么需要: ① knowledge_document 存提取清洗后的全文(供留档/排查/潜在整篇检索, MEDIUMTEXT
--   16MB 上限); ② knowledge_chunk 父块冗余存文件名, 查父块时无需 join knowledge_document。
-- 执行方式(不随 spring.sql.init 自动跑, 只对新库生效):
--   mysql -h127.0.0.1 -P3306 -uroot -p ai_agent_db < src/main/resources/db/upgrade/2026-10-full-text-and-chunk-file-name.sql
-- 幂等性: ADD COLUMN 无 IF NOT EXISTS, 重复执行会报 1060 Duplicate column, 属预期(执行一次即可)。
-- 列位置用 AFTER 对齐新库建表顺序(create_table.sql / schema-mysql-extra.sql), 避免列序不一致。
-- 存量策略: 两列对存量行为 NULL/空——不回填, 后续"重新处理"时自动写入(与 file_hash 先例一致)。

ALTER TABLE knowledge_document
    ADD COLUMN full_text MEDIUMTEXT NULL
        COMMENT '文档全部内容(提取清洗后的全文; 仅新入库/重处理写入, 存量留空)'
        AFTER error_message;

ALTER TABLE knowledge_chunk
    ADD COLUMN file_name VARCHAR(255) NOT NULL DEFAULT ''
        COMMENT '所属文档文件名(冗余, 避免查父块时 join knowledge_document)'
        AFTER doc_id;
