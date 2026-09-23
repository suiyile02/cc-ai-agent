package com.ai.config.props;

import lombok.Data;

/**
 * 会话自动标题({@code app.session-title.*}): 首轮把"未命名会话"填成有意义的标题。
 *
 * <p>两段式——先用问题前若干字同步占位(零等待, 保证任何情况下都有标题),
 * 再用模型概括精修(异步, 与本轮回答并行)。详见 {@code com.ai.session.service.SessionTitleService}。
 */
@Data
public class SessionTitleProps {

    /** 总开关(关闭则标题保持为空, 前端显示"未命名会话") */
    private boolean enabled = true;
    /** 兜底标题截取长度(按 UTF-16 单元计, 不会切开 emoji 代理对) */
    private int fallbackChars = 24;
    /** 是否调用模型精修(关闭则一直停留在兜底的截断标题, 零模型开销) */
    private boolean modelEnabled = true;
    /** 精修标题长度上限(模型不守规矩时的硬截断保护) */
    private int maxChars = 30;
    /** 精修调用超时毫秒(超时放弃, 保留兜底标题) */
    private long timeoutMs = 8000;
}
