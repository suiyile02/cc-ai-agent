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

import java.math.BigDecimal;

/**
 * 订单(示例业务数据表, 供 Agent 工具查询演示)。
 * 说明：不在 db/create_table.sql 中(演示数据)，由 JPA ddl-auto 自动创建。
 */
@Getter
@Setter
@Entity
@Table(name = "orders")
public class SalesOrder extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "customer_name", length = 64)
    private String customerName;

    @Column(name = "product_name", length = 128)
    private String productName;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    /** 待发货/已发货/已完成/已取消 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "express_no", length = 64)
    private String expressNo;
}
