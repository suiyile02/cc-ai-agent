package com.ai.agent.dto;

import java.math.BigDecimal;

/**
 * 订单信息(Agent 工具返回)。
 */
public record OrderVO(
        String orderNo,
        String customerName,
        String productName,
        BigDecimal amount,
        String status,
        String expressNo) {

    public static OrderVO notFound(String orderNo) {
        return new OrderVO(orderNo, "", "", null, "未找到", "");
    }
}
