package com.ai.system.mapper;

import com.ai.system.entity.RagDecisionLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 意图路由决策日志 Mapper。
 */
@Mapper
public interface RagDecisionLogMapper extends BaseMapper<RagDecisionLog> {
}
