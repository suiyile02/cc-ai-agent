-- =====================================================================
-- MySQL 补充建表脚本(与 create_table.sql 中的 5 张业务表互补, 全部 IF NOT EXISTS)
-- 由 spring.sql.init 在启动期幂等执行(全部 IF NOT EXISTS)。
-- 约定: 每张表带表注释, 每个列带列注释; 已有库新增/变更注释见 db/upgrade/ 下的迁移脚本。
-- =====================================================================

-- 员工(Agent 工具演示)
CREATE TABLE IF NOT EXISTS employee (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    name        VARCHAR(64)  NOT NULL COMMENT '姓名',
    department  VARCHAR(64)  NULL COMMENT '所属部门',
    position    VARCHAR(64)  NULL COMMENT '职位',
    phone       VARCHAR(32)  NULL COMMENT '联系电话',
    email       VARCHAR(128) NULL COMMENT '电子邮箱',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工演示数据(Agent 工具 queryEmployee 数据源)';

-- 订单(Agent 工具演示)
CREATE TABLE IF NOT EXISTS orders (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    order_no      VARCHAR(64)   NOT NULL COMMENT '订单号(唯一)',
    customer_name VARCHAR(64)   NULL COMMENT '客户名称',
    product_name  VARCHAR(128)  NULL COMMENT '商品名称',
    amount        DECIMAL(12,2) NULL COMMENT '订单金额(元)',
    status        VARCHAR(20)   NULL COMMENT '订单状态: 已下单/已发货/已签收等',
    express_no    VARCHAR(64)   NULL COMMENT '快递单号',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单演示数据(Agent 工具 queryOrder 数据源)';

-- 系统用户(注册/登录鉴权)
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    username   VARCHAR(64)  NOT NULL COMMENT '登录用户名(唯一)',
    password   VARCHAR(128) NOT NULL COMMENT '密码(PBKDF2 加盐哈希)',
    nickname   VARCHAR(64)  NULL COMMENT '昵称',
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: ADMIN/USER',
    status     INT          NOT NULL DEFAULT 1 COMMENT '状态: 1正常 0禁用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE INDEX uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户(注册/登录/角色鉴权)';

-- RAG 意图路由决策日志
CREATE TABLE IF NOT EXISTS rag_decision_log (
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id          VARCHAR(36) NOT NULL COMMENT '所属会话ID',
    user_id             BIGINT      NULL COMMENT '所属用户ID(授权过滤)',
    user_message        TEXT        NULL COMMENT '用户原始问题(截断500字)',
    rag_mode            VARCHAR(16) NOT NULL COMMENT '意图路由结果: KB/GENERAL',
    session_type        VARCHAR(20) NOT NULL COMMENT '会话类型: RAG/AGENT/HYBRID',
    retrieval_executed  BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否真的执行了检索; KB 且为 0 表示语义缓存命中',
    semantic_hits       INT         NOT NULL DEFAULT 0 COMMENT '语义向量路召回数量',
    keyword_hits        INT         NOT NULL DEFAULT 0 COMMENT '关键词 BM25 路召回数量',
    final_hits          INT         NOT NULL DEFAULT 0 COMMENT '最终注入上下文的命中段数(重排后 Top-K)',
    top_k               INT         NULL COMMENT '本次检索 Top-K 配置值',
    similarity_threshold DOUBLE     NULL COMMENT '本次检索相似度阈值',
    rerank_mode         VARCHAR(16) NULL COMMENT '重排模式: score/llm/none',
    duration_ms         INT         NULL COMMENT '路由与检索耗时(毫秒)',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_rag_dec_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RAG 意图路由与检索决策审计日志';

-- 会话历史滚动摘要(上下文管线: 超窗老对话压缩为摘要)
CREATE TABLE IF NOT EXISTS conversation_summary (
    conversation_id   VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '会话ID(=sessionId)',
    summary_text      TEXT        NULL COMMENT '滚动摘要文本',
    summarized_until  DATETIME    NULL COMMENT '已摘要截止的消息时间戳',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话历史滚动摘要(超窗老对话压缩存储)';

-- 上下文装配可观测日志(各段 token 占用/是否截断/改写结果)
CREATE TABLE IF NOT EXISTS context_log (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id        VARCHAR(36) NOT NULL COMMENT '所属会话ID',
    user_id           BIGINT      NULL COMMENT '所属用户ID(授权过滤)',
    user_message      TEXT        NULL COMMENT '原始用户输入(截断保存)',
    rewritten_query   TEXT        NULL COMMENT '多轮改写后的检索问题',
    intent_mode       VARCHAR(16) NULL COMMENT 'KB/GENERAL',
    system_tokens     INT         NOT NULL DEFAULT 0 COMMENT 'system 提示词段 Token 数',
    history_tokens    INT         NOT NULL DEFAULT 0 COMMENT '历史消息段 Token 数',
    summary_tokens    INT         NOT NULL DEFAULT 0 COMMENT '历史摘要段 Token 数',
    rag_tokens        INT         NOT NULL DEFAULT 0 COMMENT 'RAG 检索上下文段 Token 数',
    user_tokens       INT         NOT NULL DEFAULT 0 COMMENT '本轮用户输入 Token 数',
    total_tokens      INT         NOT NULL DEFAULT 0 COMMENT '合计 Token 数(各段之和)',
    model_max_tokens  INT         NULL COMMENT '模型上下文窗口上限(Token)',
    history_messages  INT         NOT NULL DEFAULT 0 COMMENT '纳入的历史消息条数',
    truncated         BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '是否因 Token 预算不足发生截断',
    duration_ms       INT         NULL COMMENT '本轮问答总耗时(毫秒)',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_ctx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上下文装配审计日志(各段 Token 占用/截断/查询改写)';
