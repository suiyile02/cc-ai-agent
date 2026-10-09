package com.ai.knowledge.ingestion;

import com.ai.common.Strings;

/**
 * 入库失败的友好文案映射(不向记录写入原始英文异常/堆栈)。
 *
 * <p>空文件已在上传入口拦截(FILE_EMPTY), 此处多为"非空但解析不出有效文本"、乱码,
 * 或<b>向量化阶段失败</b>(嵌入接口拒绝, 如单次条数超限/模型不可用/网络/限流)。
 * 其余截断到 500 字符。抽成纯函数便于单测。
 */
final class IngestionFailureMessages {

    private IngestionFailureMessages() {
    }

    /** 嵌入/向量化失败的中文兜底(识别 OpenAI 兼容客户端的各类 4xx/5xx 错误) */
    private static final String EMBEDDING_FALLBACK =
            "文档向量化失败：嵌入接口拒绝本次请求(单次条数超限或模型暂不可用)，请稍后重试或联系管理员检查模型配置";

    /**
     * 异常 → 面向用户记录的中文失败原因。
     *
     * @param e 入库阶段异常
     * @return 友好中文文案(截断 500)
     */
    static String of(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return "入库失败";
        }
        if (msg.contains("未能从文档中解析出有效文本")) {
            return "文件内容为空或无法解析出有效文本(可能为纯图片/音视频/扫描件, 或格式不受支持)";
        }
        if (msg.contains("疑似乱码")) {
            return "文档内容疑似乱码(编码异常)，无法入库";
        }
        if (isEmbeddingFailure(msg)) {
            return EMBEDDING_FALLBACK;
        }
        return Strings.truncate(msg, 500);
    }

    /**
     * 判定是否为嵌入/向量化阶段失败。
     *
     * <p>匹配 OpenAI 兼容客户端(DashScope)抛出的错误特征: 请求 4xx/5xx、批量参数非法、
     * 限流、内部错误等。基于消息特征而非异常类型——生产里 BadRequestException 等类型
     * 由 Spring AI 的客户端层包装, 消息前缀不固定, 特征子串判定更稳。
     */
    private static boolean isEmbeddingFailure(String msg) {
        String m = msg.toLowerCase();
        return m.contains("batch size is invalid")
                || m.contains("invalidparameter")
                || m.contains("internalerror.algo")
                || m.contains("invalid api key")
                || m.contains("insufficient balance")
                || m.contains("rate limit")
                || (m.contains("embedding") && (m.contains("400") || m.contains("401")
                        || m.contains("429") || m.contains("500") || m.contains("503")));
    }
}
