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
     * 获取当前请求上送的设备位（{@code 类型:实例}，老客户端为裸类型码）。
     *
     * <p>这里刻意不再过 {@code DeviceType.codeOf}：那个归一化会把实例 ID 一并洗掉，
     * 「同一端只留 N 台」就再也判不出对面到底是几台机器。清洗与兜底改由
     * {@link DeviceIds#normalize} 负责，它保住实例段的同时仍把未知类型回落成 {@code web}。
     */
    public static String getDevice() {
        HttpServletRequest request = getRequest();
        if (request != null) {
            String device = request.getParameter("deviceId");
            if (device != null && !device.isBlank()) {
                return DeviceIds.normalize(device);
            }
            String header = request.getHeader("X-Device-Id");
            if (header != null && !header.isBlank()) {
                return DeviceIds.normalize(header);
            }
        }
        return DeviceType.WEB.getCode();
    }

    /**
     * 取<b>已登录会话</b>的设备位，与 {@link #getDevice()} 的区别是它信 Sa-Token 而不是信请求参数。
     *
     * <p>凡是要拿设备位去踢人、撤销、写在线键的动作都必须用它：客户端可以在请求上随便
     * 伪造一个 {@code deviceId}，若拿它去定位要注销的会话，就等于「发一条 HTTP 请求就能把
     * 别人另一台设备挤下线」。登录时写进会话的那一份才是可信的。
     *
     * @return 未登录时返回 {@code null}
     */
    public static String getLoginDevice() {
        try {
            if (!StpUtil.isLogin()) {
                return null;
            }
            return DeviceIds.slot(StpUtil.getLoginDeviceType(), StpUtil.getLoginDeviceId());
        } catch (Exception ignored) {
            // 会话缺失或被顶下线时都按「拿不到设备位」处理，由调用方决定兜底
            return null;
        }
    }

    /**
     * 取当前请求的 User-Agent（截到 {@value DeviceIds#MAX_NAME_LENGTH} 内）。
     *
     * <p>与下线留痕、异常登录提示同用一份截断口径：它是完全由客户端决定的字符串，
     * 不截断就会撞列宽报错，也会在提示里塞进一句几 KB 的垃圾。
     */
    public static String getUserAgent() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return null;
        }
        return DeviceIds.sanitizeName(request.getHeader("User-Agent"));
    }

    /**
     * 取客户端自报的设备友好名（{@code X-Device-Name}，如「HUAWEI Mate60」「Chrome 131」）。
     *
     * <p>只用于展示，不参与任何判定：这个头由客户端自己填，伪造它最多让自己的设备列表里
     * 挂一个怪名字，改不了互斥与鉴权结果。
     */
    public static String getDeviceName() {
        HttpServletRequest request = getRequest();
        if (request == null) {
            return null;
        }
        return DeviceIds.sanitizeName(request.getHeader("X-Device-Name"));
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
