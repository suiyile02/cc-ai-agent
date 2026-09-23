package com.ai.config.props;

import lombok.Data;

/** 并发防护({@code app.concurrency.*}): 单用户并发对话上限(B3)。 */
@Data
public class ConcurrencyProps {

    /** 单用户最大并发对话数 */
    private int maxConcurrentPerUser = 3;
    /** 获取并发名额的等待时长毫秒(超时拒绝) */
    private long acquireTimeoutMs = 1000;
}
