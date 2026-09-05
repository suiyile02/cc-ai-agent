package com.ai.entity;

import com.ai.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 员工(示例业务数据表, 供 Agent 工具查询演示)。
 * 说明：不在 db/create_table.sql 中(演示数据)，由 JPA ddl-auto 自动创建。
 */
@Getter
@Setter
@Entity
@Table(name = "employee")
public class Employee extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "department", length = 64)
    private String department;

    @Column(name = "position", length = 64)
    private String position;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email", length = 128)
    private String email;
}
