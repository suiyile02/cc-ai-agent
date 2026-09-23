package com.ai.config.props;

import lombok.Data;

/** 日志保留期清理({@code app.log-retention.*}): 四类审计日志按保留天数定时清理(P2-4)。 */
@Data
public class LogRetentionProps {

    /** 是否启用定时清理 */
    private boolean enabled = true;
    /** 四类审计日志保留天数 */
    private int retentionDays = 180;
    /** 执行 cron(默认每天凌晨 3 点) */
    private String cron = "0 0 3 * * ?";
}
