package com.ai.agent.mapper;

import com.ai.agent.entity.Employee;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 员工 Mapper(Agent 工具演示用)。
 */
@Mapper
public interface EmployeeMapper extends BaseMapper<Employee> {
}
