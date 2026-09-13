package com.im.common.exception;

import com.im.common.api.ResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常，由 {@code GlobalExceptionHandler} 统一转换为 {@code Result}。
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(ResultCode resultCode, Object... args) {
        super(resultCode.format(args));
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
    }

    /**
     * 条件成立时抛出异常。
     */
    public static void throwIf(boolean condition, ResultCode resultCode, Object... args) {
        if (condition) {
            throw new BusinessException(resultCode, args);
        }
    }

    /**
     * 条件不成立时抛出异常。
     */
    public static void throwUnless(boolean condition, ResultCode resultCode, Object... args) {
        if (!condition) {
            throw new BusinessException(resultCode, args);
        }
    }
}
