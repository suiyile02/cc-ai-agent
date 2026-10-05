package com.ai.system.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 严格知识库模式热切换请求。
 *
 * @param enabled true=严格知识库模式(无据拒答/工具裁决); false=宽松自由作答
 */
public record KbOnlySwitchRequest(@NotNull Boolean enabled) {
}
