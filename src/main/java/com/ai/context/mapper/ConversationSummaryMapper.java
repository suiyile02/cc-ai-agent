package com.ai.context.mapper;

import com.ai.context.entity.ConversationSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话历史滚动摘要 Mapper。
 */
@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummary> {
}
