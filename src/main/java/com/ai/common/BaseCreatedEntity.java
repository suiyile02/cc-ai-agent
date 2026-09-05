package com.ai.common;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;

/**
 * 审计字段基类(仅创建时间)：created_at。
 * 对应建表脚本中只有 created_at 的表(chat_log / tool_call_log)。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseCreatedEntity {

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
