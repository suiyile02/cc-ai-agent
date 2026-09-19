package com.ai.agent.dto;

/**
 * 员工信息(Agent 工具返回)。
 */
public record EmployeeVO(
        String name,
        String department,
        String position,
        String phone,
        String email) {

    /**
     * 构造"未查到该员工"的结果（工具据此回答，而不是抛异常中断对话）。
     *
     * @param name 查询的员工姓名
     * @return 部门/职位标注为"未找到"的 VO
     */
    public static EmployeeVO notFound(String name) {
        return new EmployeeVO(name, "未找到", "未找到", "", "");
    }
}
