package com.im.common.exception;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import cn.dev33.satoken.exception.SaTokenException;
import com.im.common.api.Result;
import com.im.common.api.ResultCode;
import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：所有异常统一转换为 {@link Result}，HTTP 状态码保持 200，业务语义由 code 表达。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * Sa-Token 未登录。
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供登录凭证";
            case NotLoginException.INVALID_TOKEN -> "登录凭证无效";
            case NotLoginException.TOKEN_TIMEOUT -> "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "账号已在其他设备登录";
            case NotLoginException.KICK_OUT -> "账号已被强制下线";
            case NotLoginException.TOKEN_FREEZE -> "登录凭证已被冻结";
            case NotLoginException.NO_PREFIX -> "未按要求前缀提交登录凭证";
            default -> ResultCode.UNAUTHORIZED.getMessage();
        };
        log.warn("未登录访问: type={}, message={}", e.getType(), message);
        return Result.fail(ResultCode.UNAUTHORIZED.getCode(), message);
    }

    /**
     * Sa-Token 权限不足。
     */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermission(NotPermissionException e) {
        log.warn("权限不足: permission={}", e.getPermission());
        return Result.fail(ResultCode.FORBIDDEN.getCode(), "缺少权限：" + e.getPermission());
    }

    /**
     * Sa-Token 角色不足。
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e) {
        log.warn("角色不足: role={}", e.getRole());
        return Result.fail(ResultCode.FORBIDDEN.getCode(), "缺少角色：" + e.getRole());
    }

    /**
     * Sa-Token 二级认证未通过。
     */
    @ExceptionHandler(NotSafeException.class)
    public Result<Void> handleNotSafe(NotSafeException e) {
        log.warn("二级认证未通过: service={}", e.getService());
        return Result.fail(ResultCode.FORBIDDEN.getCode(), "该操作需要二次认证");
    }

    /**
     * Sa-Token 账号封禁。
     */
    @ExceptionHandler(DisableServiceException.class)
    public Result<Void> handleDisable(DisableServiceException e) {
        log.warn("账号被封禁: service={}", e.getService());
        return Result.fail(ResultCode.USER_DISABLED.getCode(), "账号已被封禁，剩余 " + e.getDisableTime() + " 秒");
    }

    /**
     * 其他 Sa-Token 异常。
     */
    @ExceptionHandler(SaTokenException.class)
    public Result<Void> handleSaToken(SaTokenException e) {
        log.warn("鉴权异常: {}", e.getMessage());
        return Result.fail(ResultCode.FORBIDDEN.getCode(), e.getMessage());
    }

    /**
     * @RequestBody 上的 JSR-303 校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED, message);
    }

    /**
     * 表单对象绑定校验失败。
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED, message);
    }

    /**
     * 方法参数上的约束校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", message);
        return Result.fail(ResultCode.VALIDATE_FAILED, message);
    }

    /**
     * 缺少必填请求参数。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.BAD_REQUEST, "缺少参数 " + e.getParameterName());
    }

    /**
     * 参数类型不匹配。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(ResultCode.BAD_REQUEST, "参数 " + e.getName() + " 类型不正确");
    }

    /**
     * 请求体无法解析。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, "请求体格式错误");
    }

    /**
     * 请求方法不支持。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED.getCode(), "不支持 " + e.getMethod() + " 请求");
    }

    /**
     * 上传文件超限。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限: {}", e.getMessage());
        return Result.fail(ResultCode.UPLOAD_TOO_LARGE);
    }

    /**
     * 唯一索引冲突，多用于消息幂等、好友关系等场景。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return Result.fail(ResultCode.BUSINESS_ERROR.getCode(), "数据已存在，请勿重复提交");
    }

    /**
     * Spring MVC 标准错误响应（404、405 等）。
     *
     * <p>{@link ErrorResponse} 只是接口而非 Throwable，不能直接用作 {@code @ExceptionHandler} 的值，
     * 因此按其三个常见实现类分别注册，最终汇聚到同一个转换方法。
     */
    @ExceptionHandler(ErrorResponseException.class)
    public Result<Void> handleErrorResponseException(ErrorResponseException e) {
        return toErrorResponse(e);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public Result<Void> handleNoHandlerFound(ServletException e) {
        return toErrorResponse((ErrorResponse) e);
    }

    private Result<Void> toErrorResponse(ErrorResponse e) {
        int status = e.getStatusCode().value();
        ResultCode code = switch (status) {
            case 404 -> ResultCode.NOT_FOUND;
            case 405 -> ResultCode.METHOD_NOT_ALLOWED;
            case 401 -> ResultCode.UNAUTHORIZED;
            case 403 -> ResultCode.FORBIDDEN;
            default -> ResultCode.BAD_REQUEST;
        };
        log.warn("请求处理失败: status={}, code={}", status, code.getCode());
        return Result.fail(code);
    }

    /**
     * 兜底处理，避免异常堆栈泄漏给客户端。
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
