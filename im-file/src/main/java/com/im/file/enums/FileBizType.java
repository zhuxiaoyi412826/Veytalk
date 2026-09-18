package com.im.file.enums;

import lombok.Getter;

import java.util.Locale;
import java.util.Set;

/**
 * 文件业务类型，对应 {@code im_file.biz_type}，同时决定扩展名白名单与大小上限。
 *
 * <p>用白名单而不是黑名单：黑名单永远列不全，一个 {@code .exe} 改名成 {@code .jpg} 绕不过内容校验、
 * 但一个 {@code .html} 只要被同源 inline 渲染出来就是存储型 XSS。这里只放行明确需要的类型，
 * 其余一律拒绝，新增类型必须改代码而不是改配置——这正是想让「加一种文件类型」变得有摩擦的地方。
 *
 * <p>刻意不放行 {@code svg}：它是图片格式里唯一能内嵌脚本的，一旦从本站同源返回就等于把执行权交给了上传者。
 */
@Getter
public enum FileBizType {

    /** 用户头像 */
    AVATAR("avatar", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /** 聊天图片 */
    CHAT_IMAGE("chat_image", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /** 聊天文件 */
    CHAT_FILE("chat_file", Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json", "xml", "yml", "yaml", "log",
            "py", "js", "ts", "java", "c", "cpp", "h", "cs", "go", "sh", "ps1", "bat",
            "html", "css", "sql",
            "zip", "rar", "7z", "gz", "tar",
            // 安装包：下载时 Content-Type 归为 octet-stream、非图片/视频不会 inline，
            // 浏览器不会在本站源内渲染或执行它，风险与传一个 zip 等价（是否运行由接收方知情决定）
            "exe", "msi",
            "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv",
            "mp3", "wav", "flac", "aac", "ogg", "m4a")),

    /** 聊天语音 */
    CHAT_VOICE("chat_voice", Set.of("mp3", "wav", "aac", "m4a", "ogg", "amr", "flac"));

    private final String code;
    private final Set<String> allowedExtensions;

    FileBizType(String code, Set<String> allowedExtensions) {
        this.code = code;
        this.allowedExtensions = allowedExtensions;
    }

    /**
     * 按 code 查找，未知返回 {@code null}。
     *
     * <p>这里不回退到某个默认值：调用方拿到 null 后应当直接拒绝请求。
     * 如果未知类型悄悄落到 {@link #CHAT_FILE}，客户端就能自己发明一个 bizType
     * 来试探另一套白名单，鉴权规则会随配置漂移。
     */
    public static FileBizType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (FileBizType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 扩展名是否被允许，入参为不含点的小写扩展名。
     */
    public boolean allows(String ext) {
        return ext != null && allowedExtensions.contains(ext.toLowerCase(Locale.ROOT));
    }

    /**
     * 是否为图片类：决定下载时默认用 {@code inline} 还是 {@code attachment}，
     * 图片必须能在浏览器里直接渲染，否则聊天窗口里全是下载按钮。
     */
    public boolean isImage() {
        return this == AVATAR || this == CHAT_IMAGE;
    }
}
