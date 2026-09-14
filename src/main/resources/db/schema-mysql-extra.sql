-- =====================================================================
-- MySQL 补充建表脚本(与 create_table.sql 中的 5 张业务表互补, 全部 IF NOT EXISTS)
-- 由 spring.sql.init 在启动期幂等执行(全部 IF NOT EXISTS)。
-- =====================================================================

-- 员工(Agent 工具演示)
CREATE TABLE IF NOT EXISTS employee (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    department  VARCHAR(64)  NULL,
    position    VARCHAR(64)  NULL,
    phone       VARCHAR(32)  NULL,
    email       VARCHAR(128) NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 订单(Agent 工具演示)
CREATE TABLE IF NOT EXISTS orders (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(64)   NOT NULL,
    customer_name VARCHAR(64)   NULL,
    product_name  VARCHAR(128)  NULL,
    amount        DECIMAL(12,2) NULL,
    status        VARCHAR(20)   NULL,
    express_no    VARCHAR(64)   NULL,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 系统用户(注册/登录鉴权)
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL,
    password   VARCHAR(128) NOT NULL COMMENT 'PBKDF2 加盐哈希',
    nickname   VARCHAR(64)  NULL,
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色: ADMIN/USER',
    status     INT          NOT NULL DEFAULT 1 COMMENT '1正常 0禁用',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- RAG 意图路由决策日志
CREATE TABLE IF NOT EXISTS rag_decision_log (
    id                  BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id          VARCHAR(36) NOT NULL,
    user_id             BIGINT      NULL COMMENT '所属用户ID(授权过滤)',
    user_message        TEXT        NULL,
    rag_mode            VARCHAR(16) NOT NULL COMMENT 'KB/GENERAL',
    session_type        VARCHAR(20) NOT NULL,
    retrieval_executed  BOOLEAN     NOT NULL DEFAULT FALSE,
    semantic_hits       INT         NOT NULL DEFAULT 0,
    keyword_hits        INT         NOT NULL DEFAULT 0,
    final_hits          INT         NOT NULL DEFAULT 0,
    top_k               INT         NULL,
    similarity_threshold DOUBLE     NULL,
    rerank_mode         VARCHAR(16) NULL,
    duration_ms         INT         NULL,
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rag_dec_session (session_id),
    INDEX idx_rag_dec_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话历史滚动摘要(上下文管线: 超窗老对话压缩为摘要)
CREATE TABLE IF NOT EXISTS conversation_summary (
    conversation_id   VARCHAR(36) NOT NULL PRIMARY KEY COMMENT '会话ID(=sessionId)',
    summary_text      TEXT        NULL COMMENT '滚动摘要文本',
    summarized_until  DATETIME    NULL COMMENT '已摘要截止的消息时间戳',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 上下文装配可观测日志(各段 token 占用/是否截断/改写结果)
CREATE TABLE IF NOT EXISTS context_log (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id        VARCHAR(36) NOT NULL,
    user_id           BIGINT      NULL COMMENT '所属用户ID(授权过滤)',
    user_message      TEXT        NULL COMMENT '原始用户输入(截断保存)',
    rewritten_query   TEXT        NULL COMMENT '多轮改写后的检索问题',
    intent_mode       VARCHAR(16) NULL COMMENT 'KB/GENERAL',
    system_tokens     INT         NOT NULL DEFAULT 0,
    history_tokens    INT         NOT NULL DEFAULT 0,
    summary_tokens    INT         NOT NULL DEFAULT 0,
    rag_tokens        INT         NOT NULL DEFAULT 0,
    user_tokens       INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    model_max_tokens  INT         NULL,
    history_messages  INT         NOT NULL DEFAULT 0,
    truncated         BOOLEAN     NOT NULL DEFAULT FALSE,
    duration_ms       INT         NULL,
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ctx_session (session_id),
    INDEX idx_ctx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
