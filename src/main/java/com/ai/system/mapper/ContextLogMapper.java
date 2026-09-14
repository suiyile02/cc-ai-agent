package com.ai.system.mapper;

import com.ai.system.entity.ContextLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上下文装配可观测日志 Mapper。
 */
@Mapper
public interface ContextLogMapper extends BaseMapper<ContextLog> {
}
