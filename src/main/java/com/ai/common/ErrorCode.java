package com.ai.common;

import lombok.Getter;

/**
 * 业务错误码(与《功能需求开发文档》第 8.2 节一致, 1001~5002)。
 */
@Getter
public enum ErrorCode {

    // 文件相关
    FILE_NAME_EMPTY(1001, "文件名为空"),
    FILE_TYPE_NOT_SUPPORTED(1002, "不支持的文件格式，仅支持 PDF/DOCX/TXT/MD"),
    FILE_TOO_LARGE(1003, "文件大小超过限制（单文件最大 50MB）"),
    FILE_SAVE_FAILED(1004, "文件保存失败"),

    // 知识库相关
    DOCUMENT_NOT_FOUND(2001, "文档不存在"),
    DOCUMENT_PROCESSING(2002, "文档正在处理中，请稍后再试"),

    // 对话相关
    SESSION_NOT_FOUND(3001, "会话不存在或已归档"),
    RAG_SEARCH_FAILED(3002, "知识库检索失败"),

    // 系统相关
    PARAM_ERROR(4001, "参数错误"),
    SYSTEM_ERROR(5001, "系统内部错误"),
    AUTH_FAILED(5002, "认证失败"),
    AI_NOT_CONFIGURED(5003, "AI 服务未配置或暂时不可用，请检查模型 API Key/网络后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return SYSTEM_ERROR;
    }
}
