package com.ai.config;

import com.ai.entity.Employee;
import com.ai.entity.SalesOrder;
import com.ai.repository.EmployeeRepository;
import com.ai.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 演示数据初始化：当员工/订单表为空时写入示例业务数据，
 * 便于直接体验 Agent 工具调用(queryEmployee / queryOrder)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final EmployeeRepository employeeRepository;
    private final SalesOrderRepository salesOrderRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedEmployees();
        seedOrders();
    }

    private void seedEmployees() {
        if (employeeRepository.count() > 0) {
            return;
        }
        employeeRepository.save(employee("张三", "研发部", "后端工程师", "13800000001", "zhangsan@demo.com"));
        employeeRepository.save(employee("李四", "人力资源部", "HRBP", "13800000002", "lisi@demo.com"));
        employeeRepository.save(employee("王五", "销售部", "销售经理", "13800000003", "wangwu@demo.com"));
        log.info("已初始化 3 条示例员工数据");
    }

    private Employee employee(String name, String dept, String pos, String phone, String email) {
        Employee e = new Employee();
        e.setName(name);
        e.setDepartment(dept);
        e.setPosition(pos);
        e.setPhone(phone);
        e.setEmail(email);
        return e;
    }

    private void seedOrders() {
        if (salesOrderRepository.count() > 0) {
            return;
        }
        salesOrderRepository.save(order("SO20260101001", "张三", "AI 智能问答一体机", "25999.00", "已发货", "SF1234567890"));
        salesOrderRepository.save(order("SO20260101002", "李四", "企业知识库订阅(年)", "19999.00", "已完成", "SF2234567890"));
        salesOrderRepository.save(order("SO20260101003", "王五", "智能客服 API 流量包", "9999.00", "待发货", ""));
        log.info("已初始化 3 条示例订单数据");
    }

    private SalesOrder order(String orderNo, String customer, String product,
            String amount, String status, String expressNo) {
        SalesOrder o = new SalesOrder();
        o.setOrderNo(orderNo);
        o.setCustomerName(customer);
        o.setProductName(product);
        o.setAmount(new BigDecimal(amount));
        o.setStatus(status);
        o.setExpressNo(expressNo);
        return o;
    }
}
