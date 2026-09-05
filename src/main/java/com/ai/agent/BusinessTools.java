package com.ai.agent;

import com.ai.dto.EmployeeVO;
import com.ai.dto.OrderVO;
import com.ai.entity.Employee;
import com.ai.entity.SalesOrder;
import com.ai.repository.EmployeeRepository;
import com.ai.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final EmployeeRepository employeeRepository;
    private final SalesOrderRepository salesOrderRepository;

    @Tool(description = "根据员工姓名查询其部门、职位、电话和邮箱。当用户询问某位员工的信息时调用。")
    public EmployeeVO queryEmployee(
            @ToolParam(description = "员工姓名，例如：张三") String name) {
        log.info("Agent 调用工具 queryEmployee: name={}", name);
        return employeeRepository.findFirstByName(name)
                .map(this::toVO)
                .orElseGet(() -> EmployeeVO.notFound(name));
    }

    @Tool(description = "根据订单号查询订单状态、金额和物流单号。当用户询问某个订单的情况时调用。")
    public OrderVO queryOrder(
            @ToolParam(description = "订单编号，例如：SO20260101001") String orderNo) {
        log.info("Agent 调用工具 queryOrder: orderNo={}", orderNo);
        return salesOrderRepository.findByOrderNo(orderNo)
                .map(this::toVO)
                .orElseGet(() -> OrderVO.notFound(orderNo));
    }

    @Tool(description = "获取当前系统日期和时间。当用户询问“现在几点”“今天几号/星期几”时调用。")
    public String getCurrentTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private EmployeeVO toVO(Employee e) {
        return new EmployeeVO(e.getName(), e.getDepartment(), e.getPosition(),
                e.getPhone(), e.getEmail());
    }

    private OrderVO toVO(SalesOrder o) {
        return new OrderVO(o.getOrderNo(), o.getCustomerName(), o.getProductName(),
                o.getAmount(), o.getStatus(), o.getExpressNo());
    }
}
