-- =============================================================================
-- 一次性迁移: knowledge_document 增加"文件内容 SHA-256"判重列 + 唯一索引(P3-9)
-- =============================================================================
-- 为什么需要: 同内容文档重复入库会平分检索名额(topK 被重复内容吃掉), 且小语料下
--   BM25 对任何问题都能凑满命中——判重键为文件内容 SHA-256(与文件名无关, 全局范围)。
-- 执行方式(不随 spring.sql.init 自动跑, 只对新库生效):
--   mysql -h127.0.0.1 -P3306 -uroot -p ai_agent_db < src/main/resources/db/upgrade/2026-10-document-file-hash.sql
-- 幂等性: ADD COLUMN/ADD INDEX 无 IF NOT EXISTS, 重复执行会报 1060/1061, 属预期(执行一次即可)。
-- 列位置用 AFTER 对齐新库建表顺序(create_table.sql), 避免新库与存量库列序不一致。
-- 历史行该列为 NULL: 不回填(不参与判重), 后续"重新处理"或入库成功时自动补填——
--   同内容的存量文档重复上传在补填前不会被拦截, 属已知边界(见 roadmap P3-9)。

ALTER TABLE knowledge_document
    ADD COLUMN file_hash CHAR(64) NULL
        COMMENT '文件内容 SHA-256(全库判重键；上传时流式计算，存量行为 NULL 不参与判重，入库成功时补填)'
        AFTER file_size;

ALTER TABLE knowledge_document
    ADD UNIQUE INDEX uk_file_hash (file_hash);
