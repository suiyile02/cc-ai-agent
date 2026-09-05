package com.ai.common;

import lombok.Getter;

/**
 * 统一响应体。所有 Controller 必须返回 {@code Result<T>}，禁止直接返回实体。
 */
@Getter
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return new Result<>(200, "操作成功", null);
    }

    /** 仅自定义成功消息(无 data), 用于 delete/archive 等返回 Result&lt;Void&gt; 的接口 */
    public static <T> Result<T> ok(String message) {
        return new Result<>(200, message, null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
