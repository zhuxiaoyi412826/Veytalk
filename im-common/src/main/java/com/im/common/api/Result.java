package com.im.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * @param <T> 业务数据类型
 */
@Data
@Schema(description = "统一响应结构")
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** MDC 中链路追踪 ID 的键名 */
    public static final String TRACE_ID = "traceId";

    @Schema(description = "业务状态码，200 表示成功", example = "200")
    private int code;

    @Schema(description = "提示信息", example = "操作成功")
    private String message;

    @Schema(description = "业务数据")
    private T data;

    @Schema(description = "链路追踪 ID，排查问题时提供给后端")
    private String traceId;

    @Schema(description = "服务端时间戳（毫秒）")
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
        this.traceId = MDC.get(TRACE_ID);
    }

    public static <T> Result<T> ok() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> ok(T data, String message) {
        return build(ResultCode.SUCCESS.getCode(), message, data);
    }

    public static <T> Result<T> fail(ResultCode resultCode, Object... args) {
        return build(resultCode.getCode(), resultCode.format(args), null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return build(code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return build(ResultCode.BUSINESS_ERROR.getCode(), message, null);
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    @Schema(description = "是否成功")
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }
}
