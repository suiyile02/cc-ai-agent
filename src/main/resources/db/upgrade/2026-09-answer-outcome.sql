-- =============================================================================
-- 一次性迁移(P3-7 A 批): rag_decision_log 增加"实际出口"列 answer_outcome
-- =============================================================================
-- 为什么需要: 旧审计口径靠 `rag_mode=KB 且 retrieval_executed=false` 反推"语义缓存命中"。
--   P3-7 把检索挪到缓存查询之前(出口只能由检索事实算出), 该组合不再成立——缓存命中时
--   确实执行了检索。因此把缓存命中升为一等公民 answer_outcome=ANSWERED_FROM_CACHE,
--   并让出口整体成为可查询维度(有 KB 依据 / 拒答 / 自由作答 / 工具数据 / 缓存命中)。
-- rag_mode 保留: 历史行的 KB/GENERAL/TOOL 必须继续可解释; 混写会造出一条不可比较的时间序列。
--   新行仍照写 rag_mode, 但它自此只是诊断参考, 不参与作答判定。
-- 执行方式(不随 spring.sql.init 自动跑, 那只对新建库生效):
--   mysql -h127.0.0.1 -P3306 -uroot ai_agent_db < src/main/resources/db/upgrade/2026-09-answer-outcome.sql
-- 幂等性: MySQL 的 ADD COLUMN 无 IF NOT EXISTS, 重复执行报 1060 Duplicate column, 属预期(执行一次)。
-- 历史行该列为 NULL(语义"本列上线前无出口记录"), 统计时按 answer_outcome IS NOT NULL 过滤;
--   分界日期见 docs/optimization-roadmap.md P3-7。

ALTER TABLE rag_decision_log
    ADD COLUMN answer_outcome VARCHAR(32) NULL
        COMMENT '本轮实际出口: ANSWERED_FROM_KB/ANSWERED_FROM_CACHE/REFUSED_NO_EVIDENCE/ANSWERED_OPEN/TOOL_DATA(事后由检索事实算出, 非事前预判)'
        AFTER rag_mode;

-- 顺带修正两列注释(不改类型/不改语义, 只让口径与代码一致)
ALTER TABLE rag_decision_log
    MODIFY COLUMN rag_mode VARCHAR(16) NOT NULL
        COMMENT '意图路由预判: KB/GENERAL/TOOL(P3-7 起仅作诊断参考, 作答口径看 answer_outcome)',
    MODIFY COLUMN retrieval_executed BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '是否真的执行了检索(工具轮与 AGENT 会话为 0); 缓存命中自 P3-7 起由 answer_outcome=ANSWERED_FROM_CACHE 标识, 不再由本列反推';
