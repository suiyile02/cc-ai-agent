package com.ai.repository;

import com.ai.entity.ChatLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ChatLogRepository
        extends JpaRepository<ChatLog, Long>, JpaSpecificationExecutor<ChatLog> {
}
