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
 *
 * <p>扩展名取的是「最后一个点之后的部分」（{@code TextUtil.extension}），所以复合后缀要单独考虑：
 * {@code a.tar.gz} 解析出来是 {@code gz}、已经在白名单里，不需要同名再列一条 {@code tar.gz}
 * （列了也永不命中，反而让人以为支持复合后缀匹配）；而 {@code a.tgz} 解析出 {@code tgz}，必须单独放行。
 */
@Getter
public enum FileBizType {

    /** 用户头像（jfif 是 JPEG 的旧式封装扩展名，字节就是 JPEG，Windows「另存为图片」常见） */
    AVATAR("avatar", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "jfif")),

    /**
     * 聊天图片。avif 在列是因为它是现在的截图默认格式之一（部分系统的截图工具与剪贴板
     * 直接给 image/avif），不支持它的浏览器会退到「点卡片下载」而不是裂图，比伪造成 png 存进去好。
     */
    CHAT_IMAGE("chat_image", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jfif")),

    /** 聊天文件 */
    CHAT_FILE("chat_file", Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "json", "xml", "yml", "yaml", "log",
            "py", "js", "ts", "java", "c", "cpp", "h", "cs", "go", "sh", "ps1", "bat",
            "html", "css", "sql",
            "zip", "rar", "7z", "gz", "tar", "tgz",
            // 安装包：下载时 Content-Type 归为 octet-stream、非图片/视频不会 inline，
            // 浏览器不会在本站源内渲染或执行它，风险与传一个 zip 等价（是否运行由接收方知情决定）
            "exe", "msi",
            // 高风险格式（需求 2）：apk 等安装包、证书私钥、凭据库都是正当的传输需求，
            // 一刀切拒掉只会把人逼到别的渠道传，这里放行但由前端「发送前风险确认 + 气泡警示标签」兜底。
            // 它们都不在 FileConvert.CONTENT_TYPES 里，一律 octet-stream + attachment，不可能被同源渲染
            "apk", "apks", "appx", "msix", "deb", "rpm", "jar", "scr", "vbs", "cmd",
            "cer", "crt", "pem", "key", "p12", "pfx", "jks", "keystore", "truststore",
            "kdbx", "reg",
            "mp4", "avi", "mov", "mkv", "webm", "flv", "wmv",
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus",
            // 图片扩展名在这里也留一份：bizType 是前端按 MIME 选的（见 ChatWindow#kindOf），而某些安卓
            // 文件管理器与转发场景只给 application/octet-stream，退化成普通文件后如果不放行就会出现
            // 「图明明选了却传不上去」；下载时按扩展名解析回 image/*，与走 chat_image 没差别
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jfif")),

    /**
     * 聊天语音。opus 是现在录音与网络语音的默认编码之一（装在 Ogg 容器里），
     * Chrome / Firefox 的 {@code <audio>} 原生能播，不必再要用户转成 mp3。
     */
    CHAT_VOICE("chat_voice", Set.of("mp3", "wav", "aac", "m4a", "ogg", "amr", "flac", "opus"));

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
