package com.im.common.util;

import java.util.regex.Pattern;

/**
 * 文本处理工具：消息内容清洗、扩展名解析、摘要生成。
 */
public final class TextUtil {

    /** 匹配 script / iframe 等危险标签 */
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<\\s*/?\\s*(script|iframe|frame|object|embed|link|style|meta)[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 匹配 on* 事件属性 */
    private static final Pattern EVENT_ATTR = Pattern.compile(
            "\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);

    /** 匹配 javascript: 协议 */
    private static final Pattern JS_PROTOCOL = Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE);

    /** 连续空白（含换行） */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextUtil() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 清洗文本消息，移除可执行脚本片段，防止存储型 XSS。
     */
    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String result = SCRIPT_TAG.matcher(text).replaceAll("");
        result = EVENT_ATTR.matcher(result).replaceAll("");
        result = JS_PROTOCOL.matcher(result).replaceAll("");
        return result;
    }

    /**
     * 清洗并截断到指定长度。
     */
    public static String sanitize(String text, int maxLength) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    /**
     * 截取文件名扩展名（小写、不含点）。
     */
    public static String extension(String fileName) {
        if (isBlank(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * 生成会话列表展示用的消息摘要。
     */
    public static String summary(String content, int maxLength) {
        if (isBlank(content)) {
            return "";
        }
        String flattened = WHITESPACE.matcher(content).replaceAll(" ").trim();
        return flattened.length() > maxLength ? flattened.substring(0, maxLength) + "..." : flattened;
    }

    /**
     * 隐藏手机号中间四位，用于日志与列表展示。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 判断是否为合法的中国大陆手机号。
     */
    public static boolean isPhone(String phone) {
        return phone != null && phone.matches("^1[3-9]\\d{9}$");
    }

    /**
     * 隐藏邮箱本地部分，仅保留首尾字符，用于日志与响应展示。
     * 例如 {@code zhangsan@qq.com -> zh******an@qq.com}。
     */
    public static String maskEmail(String email) {
        if (isBlank(email)) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2) + local.charAt(local.length() - 1) + domain;
    }
}
