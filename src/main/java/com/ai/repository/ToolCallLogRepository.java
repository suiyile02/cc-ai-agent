package com.ai.repository;

import com.ai.entity.ToolCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ToolCallLogRepository
        extends JpaRepository<ToolCallLog, Long>, JpaSpecificationExecutor<ToolCallLog> {
}
