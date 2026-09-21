-- =============================================================================
-- 一次性迁移(P3-7 第一批): rag_decision_log 增加语义路"阈值过滤前最大分"列
-- =============================================================================
-- 为什么需要: 原实现把 similarityThreshold 下发给向量库过滤, 低于阈值的分块永不返回,
--   日志里能看到的分数全部 ≥阈值——分布被从底部截断。用它给 0.45 定标必然得出
--   "阈值还能再提高"的错误结论。改为向量路以 0 阈值召回、本地过滤, 并记录过滤前的最大分。
-- 执行方式(不随 spring.sql.init 自动跑, 只对新库生效):
--   mysql -h127.0.0.1 -P3306 -uroot -p ai_agent_db < src/main/resources/db/upgrade/2026-09-semantic-max-score.sql
-- 幂等性: MySQL 的 ADD COLUMN 无 IF NOT EXISTS, 重复执行会报 1060 Duplicate column, 属预期(执行一次即可)。
-- 列位置用 AFTER 对齐新库建表顺序(schema-mysql-extra.sql), 避免新库与存量库列序不一致。
-- 历史行的该列为 NULL: 不回填(旧数据本就没有未截断的观察值), 定标统计时按
--   "semantic_max_score IS NOT NULL" 过滤即可, 分界日期见 docs/optimization-roadmap.md P3-6/P3-7。

ALTER TABLE rag_decision_log
    ADD COLUMN semantic_max_score DOUBLE NULL
        COMMENT '语义路阈值过滤前的最大相似度(0=该路无结果或分数不可得); 阈值定标与相关性判据的唯一未截断观察值'
        AFTER similarity_threshold;
