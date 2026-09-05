package com.ai.dto;

/**
 * 员工信息(Agent 工具返回)。
 */
public record EmployeeVO(
        String name,
        String department,
        String position,
        String phone,
        String email) {

    public static EmployeeVO notFound(String name) {
        return new EmployeeVO(name, "未找到", "未找到", "", "");
    }
}
