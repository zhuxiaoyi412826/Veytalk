package com.im.admin.enums;

import lombok.Getter;

import java.util.Locale;

/**
 * 客户端版本的平台码表，对应 {@code im_client_release.platform}。
 *
 * <p>码表收在服务端而不是让前端自由填串：平台是「同一平台同时只能启用一个」这条
 * 互斥规则的分组键，一旦允许调用方自造平台码，就会出现 {@code windows} 与
 * {@code windows_setup} 两个「不同平台」各自启用一个安装包、官网却只认其中一个的哑巴故障。
 * 新增平台必须改代码（与 {@code FileBizType} 同一条规矩：加一种类型就该有摩擦）。
 *
 * <p>{@code displayName} 是后台下拉与官网卡片的中文名列：改一句中文文案不该动数据库，
 * 也不该让两个前端各抄一份。
 */
@Getter
public enum ReleasePlatform {

    /** Windows NSIS 安装包（官网「推荐」卡） */
    WINDOWS_SETUP("windows_setup", "Windows 安装版"),

    /** Windows 免安装便携包 */
    WINDOWS_PORTABLE("windows_portable", "Windows 免安装版"),

    /** Android APK */
    ANDROID("android", "Android 版"),

    /** macOS 安装包（预留：当前无产物，码表先占位避免日后加平台动数据库） */
    MACOS("macos", "macOS 版"),

    /** Linux 安装包（预留，同上） */
    LINUX("linux", "Linux 版");

    private final String code;
    private final String displayName;

    ReleasePlatform(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * 按 code 查找，未知返回 {@code null}——调用方拿到 null 应当直接拒绝请求，
     * 不回退到任何默认平台：静默落到 Windows 会让一个传错码的调用以为传对了。
     */
    public static ReleasePlatform of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (ReleasePlatform platform : values()) {
            if (platform.code.equals(normalized)) {
                return platform;
            }
        }
        return null;
    }
}
