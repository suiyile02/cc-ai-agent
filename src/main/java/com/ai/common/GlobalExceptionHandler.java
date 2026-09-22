package com.ai.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。返回统一 Result + 语义化 HTTP 状态(按 ErrorCode.httpStatus 映射)；
 * 不向客户端泄漏堆栈与内部实现细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：按 errorCode 映射为统一失败响应与对应 HTTP 状态。
     *
     * @param e 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(Result.fail(e.getErrorCode(), e.getMessage()));
    }

    /**
     * 缺少必填请求参数。
     *
     * @param e 缺参异常(含参数名)
     * @return 统一失败响应(4001, HTTP 400)
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("参数缺失：{}", e.getParameterName());
        return respond(ErrorCode.PARAM_ERROR, "参数 " + e.getParameterName() + " 不能为空");
    }

    /**
     * 路径/请求参数类型不匹配(如 id 传非数字)。
     *
     * @param e 类型不匹配异常
     * @return 统一失败响应(4001, HTTP 400)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配：{}", e.getName());
        return respond(ErrorCode.PARAM_ERROR, "参数 " + e.getName() + " 类型不正确");
    }

    /**
     * 请求体 JSON 不可读(格式错误/字段类型不匹配)。
     *
     * @param e 解析异常
     * @return 统一失败响应(4001, HTTP 400)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return respond(ErrorCode.PARAM_ERROR, "请求体格式错误或字段类型不匹配");
    }

    /**
     * Bean Validation 校验失败(汇总所有字段错误)。
     *
     * @param e 校验异常(含字段错误列表)
     * @return 统一失败响应(4001, HTTP 400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败：{}", message);
        return respond(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * 上传文件超过大小限制。
     *
     * @param e 上传超限异常
     * @return 统一失败响应(1003, HTTP 413)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大：{}", e.getMessage());
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getHttpStatus())
                .body(Result.fail(ErrorCode.FILE_TOO_LARGE));
    }

    /**
     * multipart 请求缺必填部件（如批量上传一个文件都没选）。
     *
     * <p>必须单独处理：它原本落到兜底 {@code Exception} 分支变成 5001/HTTP 500，
     * 而"没选文件"是用户输入问题，不该报系统内部错误。
     *
     * @param e 缺部件异常
     * @return 统一失败响应(4001, HTTP 400)
     */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Result<Void>> handleMultipart(Exception e) {
        log.warn("multipart 请求缺少必填部件：{}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.PARAM_ERROR, "请选择至少一个文件后再上传"));
    }

    /**
     * 不支持的 HTTP 方法。
     *
     * @param e 方法不支持异常
     * @return 统一失败响应(4001, HTTP 405)
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("HTTP 方法不支持：{}", e.getMethod());
        return ResponseEntity.status(405)
                .body(Result.fail(ErrorCode.PARAM_ERROR, "请求方法不支持"));
    }

    /**
     * 静态资源不存在(缺省路径)。
     *
     * @param e 资源不存在异常
     * @return 统一失败响应(4001, HTTP 404)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(Result.fail(ErrorCode.PARAM_ERROR, "资源不存在"));
    }

    /**
     * 兜底异常：记录完整堆栈, 仅向前端返回通用错误提示。
     *
     * @param e 未捕获异常
     * @return 统一失败响应(5001, HTTP 500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    /**
     * 按错误码默认文案构造带 HTTP 状态的失败响应。
     *
     * @param errorCode 错误码
     * @param message   覆盖消息
     * @return 统一失败响应
     */
    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(Result.fail(errorCode, message));
    }
}
