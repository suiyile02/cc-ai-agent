package com.ai.config;

import com.ai.agent.entity.Employee;
import com.ai.agent.entity.SalesOrder;
import com.ai.user.entity.SysUser;
import com.ai.agent.mapper.EmployeeMapper;
import com.ai.agent.mapper.SalesOrderMapper;
import com.ai.user.mapper.SysUserMapper;
import com.ai.user.security.PasswordHasher;
import com.ai.user.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 演示数据初始化：当员工/订单/用户表为空时写入示例业务数据，
 * 便于直接体验 Agent 工具调用与登录(默认管理员 admin/admin123)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final EmployeeMapper employeeMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final SysUserMapper sysUserMapper;
    private final AppProperties appProperties;

    /**
     * 应用启动完成后执行(空表才播种)。
     *
     * @param args 启动参数(未使用)
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!appProperties.getDemo().isSeedEnabled()) {
            log.info("演示数据播种已关闭(app.demo.seed-enabled=false), 跳过初始化");
            return;
        }
        seedUsers();
        seedEmployees();
        seedOrders();
    }

    /**
     * 播种默认管理员(admin/admin123), 便于首次登录体验。
     */
    private void seedUsers() {
        Long count = sysUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setPassword(PasswordHasher.encode("admin123"));
        admin.setNickname("管理员");
        admin.setRole(UserContext.ROLE_ADMIN);
        admin.setStatus(1);
        sysUserMapper.insert(admin);
        log.info("已初始化默认管理员(账号 admin, 初始密码见 README 演示说明), 请尽快修改密码");
    }

    /**
     * 播种示例员工(张三/李四/王五)。
     */
    private void seedEmployees() {
        Long count = employeeMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        employeeMapper.insert(employee("张三", "研发部", "后端工程师", "13800000001", "zhangsan@demo.com"));
        employeeMapper.insert(employee("李四", "人力资源部", "HRBP", "13800000002", "lisi@demo.com"));
        employeeMapper.insert(employee("王五", "销售部", "销售经理", "13800000003", "wangwu@demo.com"));
        log.info("已初始化 3 条示例员工数据");
    }

    /**
     * 构造员工实体。
     *
     * @param name  姓名
     * @param dept  部门
     * @param pos   职位
     * @param phone 电话
     * @param email 邮箱
     * @return 员工实体(未持久化)
     */
    private Employee employee(String name, String dept, String pos, String phone, String email) {
        Employee e = new Employee();
        e.setName(name);
        e.setDepartment(dept);
        e.setPosition(pos);
        e.setPhone(phone);
        e.setEmail(email);
        return e;
    }

    /**
     * 播种示例订单(SO20260101xxx)。
     */
    private void seedOrders() {
        Long count = salesOrderMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        salesOrderMapper.insert(order("SO20260101001", "张三", "AI 智能问答一体机", "25999.00", "已发货", "SF1234567890"));
        salesOrderMapper.insert(order("SO20260101002", "李四", "企业知识库订阅(年)", "19999.00", "已完成", "SF2234567890"));
        salesOrderMapper.insert(order("SO20260101003", "王五", "智能客服 API 流量包", "9999.00", "待发货", ""));
        log.info("已初始化 3 条示例订单数据");
    }

    /**
     * 构造订单实体。
     *
     * @param orderNo   订单号
     * @param customer  客户名
     * @param product   商品名
     * @param amount    金额(字符串, 内部转 BigDecimal)
     * @param status    状态
     * @param expressNo 物流单号
     * @return 订单实体(未持久化)
     */
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
