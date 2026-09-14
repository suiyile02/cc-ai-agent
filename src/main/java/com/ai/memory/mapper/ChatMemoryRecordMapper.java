package com.ai.memory.mapper;

import com.ai.memory.entity.ChatMemoryRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话记忆(SPRING_AI_CHAT_MEMORY) Mapper。
 */
@Mapper
public interface ChatMemoryRecordMapper extends BaseMapper<ChatMemoryRecord> {
}
