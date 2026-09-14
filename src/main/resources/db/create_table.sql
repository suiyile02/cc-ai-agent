CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
                                                     id              BIGINT       NOT NULL AUTO_INCREMENT,
                                                     conversation_id VARCHAR(36)  NOT NULL COMMENT '会话唯一标识',
                                                     content         TEXT         NOT NULL COMMENT '消息内容（JSON序列化）',
                                                     type            VARCHAR(10)  NOT NULL COMMENT '消息类型：USER/ASSISTANT/SYSTEM/TOOL',
                                                     timestamp       TIMESTAMP    NOT NULL COMMENT '消息时间戳',
                                                     PRIMARY KEY (id),
                                                     INDEX idx_conv_ts (conversation_id, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_document (
                                    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                    file_name       VARCHAR(255) NOT NULL COMMENT '文件名',
                                    file_type       VARCHAR(20)  NOT NULL COMMENT '文件类型：PDF/DOCX/TXT/MD',
                                    file_size       BIGINT       NOT NULL COMMENT '文件大小（字节）',
                                    storage_path    VARCHAR(500) NOT NULL COMMENT '文件存储路径',
                                    collection_name VARCHAR(100) NOT NULL COMMENT 'Qdrant中的集合名称',
                                    chunk_count     INT          DEFAULT 0 COMMENT '分块数量',
                                    status          TINYINT      DEFAULT 0 COMMENT '0-待处理 1-处理中 2-已完成 3-失败',
                                    error_message   TEXT         NULL COMMENT '处理失败原因',
                                    created_by      BIGINT       NOT NULL COMMENT '上传人ID',
                                    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                                    INDEX idx_collection (collection_name),
                                    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS chat_session (
                              id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                              session_id      VARCHAR(36)  NOT NULL COMMENT '会话ID（即conversation_id）',
                              user_id         BIGINT       NOT NULL COMMENT '用户ID',
                              title           VARCHAR(200) NULL COMMENT '会话标题',
                              session_type    VARCHAR(20)  NOT NULL DEFAULT 'HYBRID' COMMENT 'RAG/AGENT/HYBRID',
                              status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-已归档 1-进行中',
                              created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                              UNIQUE INDEX uk_session_id (session_id),
                              INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tool_call_log (
                               id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                               session_id      VARCHAR(36)  NOT NULL COMMENT '所属会话ID',
                               user_id         BIGINT       NULL COMMENT '所属用户ID(授权过滤)',
                               tool_name       VARCHAR(100) NOT NULL COMMENT '工具名称',
                               input_params    JSON         NOT NULL COMMENT '输入参数',
                               output_result   TEXT         NULL COMMENT '执行结果',
                               status          VARCHAR(20)  NOT NULL COMMENT 'SUCCESS/FAILED/TIMEOUT',
                               duration_ms     INT          NULL COMMENT '执行耗时（毫秒）',
                               error_message   TEXT         NULL COMMENT '错误信息',
                               created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               INDEX idx_session_id (session_id),
                               INDEX idx_user_id (user_id),
                               INDEX idx_tool_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS chat_log (
                          id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                          session_id      VARCHAR(36)  NOT NULL COMMENT '所属会话ID',
                          user_id         BIGINT       NOT NULL COMMENT '用户ID',
                          user_message    TEXT         NOT NULL COMMENT '用户提问',
                          assistant_reply TEXT         NULL COMMENT 'AI回答',
                          sources         JSON         NULL COMMENT '引用来源（文件名+位置）',
                          tool_calls      JSON         NULL COMMENT '工具调用记录',
                          model_name      VARCHAR(50)  NOT NULL COMMENT '使用的模型',
                          total_tokens    INT          NULL COMMENT '总Token消耗',
                          duration_ms     INT          NULL COMMENT '总耗时（毫秒）',
                          created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          INDEX idx_session_id (session_id),
                          INDEX idx_user_id (user_id),
                          INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

