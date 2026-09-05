package com.ai.repository;

import com.ai.entity.ChatMemoryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChatMemoryRecordRepository extends JpaRepository<ChatMemoryRecord, Long> {

    List<ChatMemoryRecord> findByConversationIdOrderByTimestampAscIdAsc(String conversationId);

    void deleteByConversationId(String conversationId);

    @Query("select distinct r.conversationId from ChatMemoryRecord r where r.conversationId is not null")
    List<String> findDistinctConversationIds();
}
