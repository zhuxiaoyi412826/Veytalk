package com.im.common.util;

import cn.dev33.satoken.stp.StpUtil;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.enums.DeviceType;
import com.im.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 登录上下文工具，统一封装 Sa-Token 的取值逻辑。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @throws BusinessException 未登录时抛出
     */
    public static Long getUserId() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }

    /**
     * 获取当前登录用户 ID，未登录返回 {@code null}。
     */
    public static Long getUserIdOrNull() {
        try {
            if (StpUtil.isLogin()) {
                return Long.valueOf(StpUtil.getLoginIdAsString());
            }
        } catch (Exception ignored) {
            // 上下文缺失时按未登录处理
        }
        return null;
    }

    /**
     * 获取当前登录 token，未登录返回 {@code null}。
     */
    public static String getToken() {
        try {
            return StpUtil.getTokenValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前登录设备标识，从 Sa-Token 的登录设备或请求参数中解析。
     */
    public static String getDevice() {
        HttpServletRequest request = getRequest();
        if (request != null) {
            String device = request.getParameter("deviceId");
            if (device != null && !device.isBlank()) {
                return DeviceType.codeOf(device);
            }
            String header = request.getHeader("X-Device-Id");
            if (header != null && !header.isBlank()) {
                return DeviceType.codeOf(header);
            }
        }
        return DeviceType.WEB.getCode();
    }

    /**
     * 判断当前登录账号是否为超级管理员。
     */
    public static boolean isAdmin() {
        try {
            return StpUtil.isLogin() && StpUtil.hasRole(ImConstants.ROLE_ADMIN);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把当前用户 ID 写入 MDC，便于日志关联。
     */
    public static void putUserIdToMdc() {
        Long userId = getUserIdOrNull();
        if (userId != null) {
            MDC.put(ImConstants.MDC_USER_ID, String.valueOf(userId));
        }
    }

    public static HttpServletRequest getRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    /**
     * 获取客户端真实 IP，兼容常见反向代理头。
     */
    public static String getClientIp() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return "unknown";
        }
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                int comma = ip.indexOf(',');
                return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
