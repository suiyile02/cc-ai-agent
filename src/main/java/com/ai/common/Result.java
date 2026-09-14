package com.ai.common;

import lombok.Getter;

/**
 * 统一响应体。所有 Controller 必须返回 {@code Result<T>}，禁止直接返回实体。
 *
 * @param <T> data 数据类型
 */
@Getter
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    /**
     * 构造统一响应。
     *
     * @param code    业务码(200=成功)
     * @param message 提示消息
     * @param data    业务数据(可空)
     */
    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 成功(无数据)。
     *
     * @param <T> 泛型类型
     * @return code=200 的响应
     *
     * <p>【未被引用】全项目无调用方——现有 Controller 的 Void 接口统一用
     * {@code ok(String message)} 携带成功提示(如"文档已删除")。
     */
    public static <T> Result<T> ok() {
        return new Result<>(200, "操作成功", null);
    }

    /**
     * 成功但仅带消息(无 data), 用于 delete/archive 等 Result&lt;Void&gt; 接口。
     *
     * @param message 成功消息
     * @param <T>     泛型类型
     * @return code=200 的响应
     */
    public static <T> Result<T> ok(String message) {
        return new Result<>(200, message, null);
    }

    /**
     * 成功并携带数据。
     *
     * @param data 业务数据
     * @param <T>  泛型类型
     * @return code=200 的响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /**
     * 成功并携带自定义消息与数据。
     *
     * @param message 成功消息
     * @param data    业务数据
     * @param <T>     泛型类型
     * @return code=200 的响应
     */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data);
    }

    /**
     * 失败(从错误码生成)。
     *
     * @param errorCode 错误码
     * @param <T>       泛型类型
     * @return 对应 code/message 的失败响应
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败(错误码 + 覆盖消息)。
     *
     * @param errorCode 错误码
     * @param message   自定义错误消息
     * @param <T>       泛型类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 失败(自定义码与消息)。
     *
     * @param code    业务码
     * @param message 错误消息
     * @param <T>     泛型类型
     * @return 失败响应
     */
    /**
     * 失败(自定义码与消息)。
     *
     * <p>【未被引用】全项目失败响应统一走 ErrorCode(见 fail(ErrorCode) 两个重载)。
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
