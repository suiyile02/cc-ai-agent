package com.ai.config.props;

import lombok.Data;

/** 主对话与流式({@code app.chat.*}), 从 {@code AppProperties.Chat} 迁出。 */
@Data
public class ChatProps {

    /** chat_log.model_name 兜底标签：优先从 ChatModel 默认选项解析实际模型名(见 ChatService) */
    private String modelLabel = "qwen3.8-max";
    /**
     * 主对话注入 enable_thinking=false(默认 true 关闭思维链)。
     *
     * <p>关闭思维链的原因(2026-09 实测):
     * <ol>
     *   <li><b>稳定性</b>: qwen3 思考模式在长推理时可能整段静默不出字(>60s),
     *       撞上 OpenAI 客户端 okhttp 的 60s read timeout, 流被重置
     *       ({@code InterruptedIOException + StreamResetException: CANCEL}),
     *       整轮回答变成一条中断提示——线上"流式对话中断"的根因;</li>
     *   <li><b>延迟</b>: 关闭后首字秒级、总耗时从 10~60s 降到 3~8s
     *       (改写/摘要早已关闭思维链, 质量无碍)。</li>
     * </ol>
     * 若确需向用户展示思考过程, 可置 false 恢复, 但需把
     * {@code stream-idle-timeout-ms} 调大或关闭以容忍长思考静默。
     */
    private boolean disableThinking = true;
    /**
     * 流式请求是否索取 usage(默认开启)。
     *
     * <p>严格遵循 OpenAI 规范的端点只在该字段置位时, 才会在末尾发一个 usage-only 分块;
     * DashScope 兼容模式实测无论是否索取都会返回(仅作保险)。不索取时
     * {@code chat_log.total_tokens} 会恒为空——流式请求注入, 同步请求不注入
     * (部分端点会拒绝 stream_options 与 stream=false 并存)。网关不认该字段时置 false。
     */
    private boolean streamIncludeUsage = true;
    /**
     * 单轮工具调用次数上限(B2, 默认 8)：Agent 工具循环次数原由模型自主决定,
     * 模型异常时可无限连环调用烧 Token。ToolCallLogAspect 每调用一次自增计数,
     * 超过上限即抛 {@code TOOL_CALL_LIMIT(6009)}, Spring AI 把该错误回传模型令其收尾作答。
     * 0=关闭该限制(不推荐)。
     */
    private int maxToolCallsPerTurn = 8;
    /**
     * 流式静默超时毫秒(默认 20s): 相邻两个增量间隔超过该值(或首增量迟迟不来)即主动终止本轮
     * 并降级为友好提示, 早于上游 okhttp 的 60s read timeout 触发。
     *
     * <p>背景: 模型长思考/网络挂起时会长时间无输出, 若不主动止损, 请求会卡到 okhttp 超时
     * 才被重置(实测整轮 61s 只返回一条 19 字提示)。0=关闭该保护(不推荐)。
     */
    private long streamIdleTimeoutMs = 20_000;
    /**
     * 严格知识库模式(默认开启)。
     *
     * <p>开启后主对话只能依据「知识库检索到的资料」或「业务工具返回结果」作答, 禁止模型用
     * 自身预训练知识回答公司内部事务; 无资料/资料无关时按固定口径友好拒答。
     * 影响范围: 提示词选择({@code PromptService.systemFor})——GENERAL 与 TOOL 不再套用
     * "可回答常识科普闲聊"的自由作答模板; {@code AGENT} 会话不受影响(本就靠工具作答)。
     *
     * <p><b>这是提示词级软约束, 不是硬保证</b>: 模型仍被调用, 极端情况仍可能不遵守。
     * 需要"保证零编造"时应在检索零命中处直接返回固定文案(硬闸门), 而非依赖本开关。
     */
    private boolean kbOnly = true;
}
