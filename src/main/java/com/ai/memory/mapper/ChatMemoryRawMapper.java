package com.ai.memory.mapper;

import com.ai.memory.entity.ChatMemoryRaw;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话原始轨迹留档(chat_memory_raw) Mapper。
 */
@Mapper
public interface ChatMemoryRawMapper extends BaseMapper<ChatMemoryRaw> {
}
