package com.ai.agent.mapper;

import com.ai.agent.entity.SalesOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单 Mapper(Agent 工具演示用)。
 */
@Mapper
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {
}
