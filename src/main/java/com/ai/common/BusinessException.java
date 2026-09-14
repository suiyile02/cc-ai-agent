package com.ai.common;

/**
 * 业务异常：Service 层抛出的受控业务错误，由 {@link GlobalExceptionHandler} 统一转为 Result。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 构造业务异常(消息取错误码默认文案)。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 构造业务异常(自定义消息覆盖默认文案)。
     *
     * @param errorCode 错误码
     * @param message   自定义错误消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 返回对应的业务错误码。
     *
     * @return 错误码枚举
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
