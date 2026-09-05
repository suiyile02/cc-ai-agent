package com.ai.repository;

import com.ai.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ChatSessionRepository
        extends JpaRepository<ChatSession, Long>, JpaSpecificationExecutor<ChatSession> {

    Optional<ChatSession> findBySessionId(String sessionId);

    boolean existsBySessionId(String sessionId);
}
