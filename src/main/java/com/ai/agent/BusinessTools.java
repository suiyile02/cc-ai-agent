package com.ai.agent;

import com.ai.agent.dto.EmployeeVO;
import com.ai.agent.dto.OrderVO;
import com.ai.agent.entity.Employee;
import com.ai.agent.entity.SalesOrder;
import com.ai.agent.mapper.EmployeeMapper;
import com.ai.agent.mapper.SalesOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Agent 业务工具(需求第 4 章)。通过 @Tool/@ToolParam 描述“何时调用 + 参数含义”，
 * 由大模型在回答过程中按需调用；调用日志由 ToolCallLogAspect 切面记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessTools {

    private final EmployeeMapper employeeMapper;
    private final SalesOrderMapper salesOrderMapper;

    /**
     * 按姓名查询员工信息(部门/职位/电话/邮箱)。
     *
     * @param name 员工姓名, 例如“张三”
     * @return 员工信息 VO(未找到时返回带“未找到”标记的 VO)
     */
    @Tool(description = "根据员工姓名查询其部门、职位、电话和邮箱。当用户询问某位员工的信息时调用。")
    public EmployeeVO queryEmployee(
            @ToolParam(description = "员工姓名，例如：张三") String name, ToolContext toolContext) {
        log.info("Agent 调用工具 queryEmployee: name={}", name);
        Employee employee = employeeMapper.selectOne(
                new LambdaQueryWrapper<Employee>().eq(Employee::getName, name));
        return employee == null ? EmployeeVO.notFound(name) : toVO(employee);
    }

    /**
     * 按订单号查询订单信息(状态/金额/物流单号)。
     *
     * @param orderNo 订单编号, 例如“SO20260101001”
     * @return 订单信息 VO(未找到时返回带“未找到”标记的 VO)
     */
    @Tool(description = "根据订单号查询订单状态、金额和物流单号。当用户询问某个订单的情况时调用。")
    public OrderVO queryOrder(
            @ToolParam(description = "订单编号，例如：SO20260101001") String orderNo, ToolContext toolContext) {
        log.info("Agent 调用工具 queryOrder: orderNo={}", orderNo);
        SalesOrder order = salesOrderMapper.selectOne(
                new LambdaQueryWrapper<SalesOrder>().eq(SalesOrder::getOrderNo, orderNo));
        return order == null ? OrderVO.notFound(orderNo) : toVO(order);
    }

    /**
     * 获取当前系统时间。
     *
     * @return 格式化后的当前时间字符串(yyyy-MM-dd HH:mm:ss)
     */
    @Tool(description = "获取当前系统日期和时间。当用户询问“现在几点”“今天几号/星期几”时调用。")
    public String getCurrentTime(ToolContext toolContext) {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 员工实体 → VO。
     *
     * @param e 员工实体
     * @return 员工 VO
     */
    private EmployeeVO toVO(Employee e) {
        return new EmployeeVO(e.getName(), e.getDepartment(), e.getPosition(),
                e.getPhone(), e.getEmail());
    }

    /**
     * 订单实体 → VO。
     *
     * @param o 订单实体
     * @return 订单 VO
     */
    private OrderVO toVO(SalesOrder o) {
        return new OrderVO(o.getOrderNo(), o.getCustomerName(), o.getProductName(),
                o.getAmount(), o.getStatus(), o.getExpressNo());
    }
}
