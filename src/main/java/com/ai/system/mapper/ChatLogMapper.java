package com.ai.system.mapper;

import com.ai.system.entity.ChatLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话日志 Mapper。
 */
@Mapper
public interface ChatLogMapper extends BaseMapper<ChatLog> {
}
