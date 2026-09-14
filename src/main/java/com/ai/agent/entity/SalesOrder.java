package com.ai.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单(示例业务数据表, 供 Agent 工具查询演示, 由 schema 脚本/数据库创建)。
 */
@Getter
@Setter
@TableName("orders")
public class SalesOrder {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号(唯一) */
    private String orderNo;

    /** 客户名 */
    private String customerName;

    /** 商品名 */
    private String productName;

    /** 金额 */
    private BigDecimal amount;

    /** 状态: 待发货/已发货/已完成/已取消 */
    private String status;

    /** 物流单号 */
    private String expressNo;

    /** 创建时间(自动填充) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间(自动填充) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
