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

    /**
     * 构造"未查到该订单"的结果（工具据此回答，而不是抛异常中断对话）。
     *
     * @param orderNo 查询的订单号
     * @return 状态标注为"未找到"的 VO
     */
    public static OrderVO notFound(String orderNo) {
        return new OrderVO(orderNo, "", "", null, "未找到", "");
    }
}
