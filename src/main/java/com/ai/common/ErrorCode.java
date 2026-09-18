package com.ai.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码(与《功能需求开发文档》第 8.2 节一致, 1001~5003; 6001+ 为用户模块扩展)。
 * 每个错误码绑定对应的 HTTP 状态码, 由 GlobalExceptionHandler 统一映射。
 */
@Getter
public enum ErrorCode {

    // 文件相关
    FILE_NAME_EMPTY(1001, "文件名为空", HttpStatus.BAD_REQUEST),
    FILE_TYPE_NOT_SUPPORTED(1002, "不支持的文件格式，仅支持 PDF/DOCX/TXT/MD", HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(1003, "文件大小超过限制（单文件最大 50MB）", HttpStatus.PAYLOAD_TOO_LARGE),
    FILE_SAVE_FAILED(1004, "文件保存失败", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_EMPTY(1005, "文件内容为空，无法上传", HttpStatus.BAD_REQUEST),

    // 知识库相关
    DOCUMENT_NOT_FOUND(2001, "文档不存在", HttpStatus.NOT_FOUND),
    DOCUMENT_PROCESSING(2002, "文档正在处理中，请稍后再试", HttpStatus.CONFLICT),

    // 对话相关
    SESSION_NOT_FOUND(3001, "会话不存在或已归档", HttpStatus.NOT_FOUND),
    RAG_SEARCH_FAILED(3002, "知识库检索失败", HttpStatus.SERVICE_UNAVAILABLE),

    // 系统相关
    PARAM_ERROR(4001, "参数错误", HttpStatus.BAD_REQUEST),
    SYSTEM_ERROR(5001, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    AUTH_FAILED(5002, "认证失败", HttpStatus.FORBIDDEN),
    AI_NOT_CONFIGURED(5003, "AI 服务未配置或暂时不可用，请检查模型 API Key/网络后重试",
            HttpStatus.SERVICE_UNAVAILABLE),
    AI_CALL_FAILED(5004, "模型服务调用失败，请稍后重试", HttpStatus.BAD_GATEWAY),

    // 用户相关
    USER_NOT_FOUND(6001, "用户不存在", HttpStatus.NOT_FOUND),
    USER_EXISTS(6002, "用户名已存在", HttpStatus.CONFLICT),
    USERNAME_OR_PASSWORD_ERROR(6003, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    USER_DISABLED(6004, "账号已被禁用", HttpStatus.FORBIDDEN),
    TOKEN_INVALID(6005, "未登录或凭证已失效", HttpStatus.UNAUTHORIZED),
    LOGIN_LOCKED(6006, "登录失败次数过多，已临时锁定", HttpStatus.TOO_MANY_REQUESTS),
    CONCURRENT_LIMIT(6010, "当前有对话进行中，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);

    private final int code;
    private final String message;
    /** 映射的 HTTP 状态码(避免业务错误一律返回 HTTP 200, 网关/监控无法识别) */
    private final HttpStatus httpStatus;

    /**
     * 构造错误码。
     *
     * @param code       数字码
     * @param message    默认错误消息
     * @param httpStatus HTTP 状态码
     */
    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
