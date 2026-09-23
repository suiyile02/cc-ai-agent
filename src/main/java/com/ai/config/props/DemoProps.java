package com.ai.config.props;

import lombok.Data;

/** 演示数据播种({@code app.demo.*}), 从 {@code AppProperties.Demo} 迁出。 */
@Data
public class DemoProps {

    /**
     * 是否在启动时播种演示数据(管理员/员工/订单, 仅空表时生效)。
     * **默认 false**——仓库自带可用口令是泄漏面；需要时显式开启，且必须同时注入
     * {@link #adminPassword}（留空时 {@code DemoDataInitializer} 会跳过播种并提示）。
     */
    private boolean seedEnabled = false;
    /** 播种默认管理员的初始口令(无默认值; 只在刻意开启播种时经 DEMO_ADMIN_PASSWORD 注入) */
    private String adminPassword = "";
}
