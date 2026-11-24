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
     *
     * <p>webm 是网页端（im-ui 文件传输助手）MediaRecorder 的兜底容器：前端已优先选
     * mp4/ogg 两种白名单内格式，但部分浏览器只支持 webm，不放行会让网页语音直接发不出去；
     * 与 {@link #CALL_RECORD} 放行 webm 的同一口径。
     */
    CHAT_VOICE("chat_voice", Set.of("mp3", "wav", "aac", "m4a", "ogg", "amr", "flac", "opus", "webm")),

    /**
     * 直播页视频板块的视频（短视频信息流）。与 {@link #CHAT_FILE} 分开：聊天视频只有同会话成员
     * 可见，而信息流视频天然面向所有登录用户，故单列一种类型，由 {@link #isPublic()} 让
     * {@code FileServiceImpl#canAccess} 免会话校验直接放行下载。
     */
    VIDEO("video", Set.of("mp4", "avi", "mov", "mkv", "webm", "flv", "wmv")),

    /**
     * 视频板块封面图。图片之所以也要公开，与 {@link #VIDEO} 同理：信息流卡片要在 {@code <img>}
     * 里直接渲染别人传的封面，逐个校验会话关系既做不到也没意义。
     */
    VIDEO_COVER("video_cover", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /**
     * 文章板块封面图。之所以与 {@link #VIDEO_COVER} 分开而不是复用，是为了让「文章封面」在
     * im_file 里自成一种 bizType，便于后续按业务归档与清理；扩展名口径与视频封面一致。
     */
    ARTICLE_COVER("article_cover", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /**
     * 文章正文内嵌图片（Markdown 里 {@code ![](url)} 引用的图）。必须公开：文章广场面向所有
     * 登录用户渲染，正文里的图不可能逐篇校验作者关系。多放行 avif（截图常见格式）。
     */
    ARTICLE_IMG("article_img", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")),

    /**
     * 直播页图片板块的图集（1~9 张，首图兼封面）。与 {@link #ARTICLE_IMG} 分开：那是文章正文的
     * 附属图片、生命周期跟着文章走，这是独立成一条作品的图片，按业务归档与清理的口径不同；
     * 也与 {@link #VIDEO_COVER} 无关——那是装饰性的封面，这是内容本体。
     *
     * <p>扩展名沿用 {@link #CHAT_IMAGE} 的口径（含 avif / jfif）：截图工具与「另存为图片」
     * 产出的就是这几类，少放一个只会让用户先把图转格式才能发。
     */
    IMAGE_WORK("image_work", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jfif")),

    /**
     * 直播页音乐板块的音频本体。与 {@link #CHAT_VOICE} 分开：那是会话内私有语音，这是面向
     * 所有登录用户的信息流内容，靠 {@link #isPublic()} 免会话校验放行下载。
     *
     * <p>不放行 webm：音乐板块收的是「一首曲子」，用户从各处存来的基本都是前八种容器；
     * 网页 MediaRecorder 录的 webm 属于 {@link #CALL_RECORD} 那类通话产物，不该混进作品库。
     */
    MUSIC("music", Set.of("mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "amr")),

    /**
     * 音乐板块封面图（可选，无封面时前端按标题占位）。扩展名与 {@link #VIDEO_COVER} 一致：
     * 封面是用户在上传表单里现挑的一张图，不必为他多开 avif 这条渲染路径。
     */
    MUSIC_COVER("music_cover", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /**
     * 小说板块封面图（可选）。与 {@link #MUSIC_COVER} 分开而不是合成一种「作品封面」：
     * {@code im_file} 里按 bizType 归档，合并后就没法只清某一板块的封面。
     */
    NOVEL_COVER("novel_cover", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp")),

    /**
     * 音视频通话录制产物（桌面端录屏 / 录音）。与 {@link #CHAT_VOICE} 分开：那是语音留言，
     * 这是一次实时通话的录制，且多为带画面的容器。
     *
     * <p>不开 {@code isPublic()}：录音录像属会话私有内容，只有同会话成员可回放，走 {@code canAccess} 的会话校验。
     * 主用桌面端 MediaRecorder 产出的 webm（流式落盘的必然选择），同时放行 mp4/m4a/opus 等常见录音编码，
     * 便于日后用 ffmpeg 转封装或改换录制容器。
     */
    CALL_RECORD("call_record", Set.of("webm", "mp4", "m4a", "opus", "wav", "mp3")),

    /**
     * 图片审核隔离区（五期）：发送端识别为违规、当场拒发后，另传上来的那一份副本。
     *
     * <p>单列一种而不是挂在 {@link #CHAT_IMAGE} 上：它不是任何会话的内容，而是一条
     * 追责证据；混进聊天类型会被「同会话成员可下载」那套口径误伤（另一个成员不该看到
     * 一张本来就没发出去的图），也不该出现在任何业务列表里。
     *
     * <p>不开 {@code isPublic()}：只有后台经 {@code system:log} 那扇门能看（上传者本人
     * 按「自己传的」仍可取回，那是他自己的图，不构成泄露）。也不进 {@code isImage()}：
     * 那一项决定普通下载接口默认 inline 还是 attachment，而这一类的字节永远只从后台
     * 那个端点出去，inline 与否由它自己写死。扩展名不放行 avif：它只用于取证，
     * 没必要为一种浏览器支持面还窄的格式多开一条渲染路径。
     */
    MODERATION_QUARANTINE("moderation_quarantine", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "jfif")),

    /**
     * 交友照片墙（最多 9 张，一行一张）。
     *
     * <p>与 {@link #AVATAR} 分开：头像是「全站辐射的一张脸」，照片墙是「资料卡里的内容」，
     * 两者的可见范围、上限与清理口径都不一样（头像只一个、照片墙可九张）。
     *
     * <p>扩展名沿用 {@link #CHAT_IMAGE}（含 avif / jfif）：用户传的是手机相册与微信存图，
     * 少放一个格式就是让用户先转格式才能传。
     *
     * <p>★ 可见性与 {@link #IMAGE_WORK} 同构：<b>开</b> {@link #isPublic()} 是因为陌生人的资料卡
     * 要渲染别人的图，逐条校验关系既做不到也没意义；而「哪一张能被他看到」由
     * {@code im_user_photo.audit_status} 在 im-user 服务层决定（只有通过的行才把地址发出去）。
     * 因此这一类的 {@code fileId} <b>只能从交友接口里下发</b>：被驳回与待审那张的 ID 不该出现在
     * 任何对外响应里，否则公开下载会替用户把它放出去（边界记在下述 README 口径）。不进
     * {@link #isImage()}：前端用带登录头的请求取成 blob 再交给 {@code <img>}，inline 与否不影响播放。
     */
    DATING_PHOTO("dating_photo", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jfif")),

    /**
     * 交友动态的附图（一条最多 9 张）。
     *
     * <p>与 {@link #DATING_PHOTO} 分开而不是合用一种「交友图片」：两者在 {@code im_file} 里
     * 的归档与清理口径不同（照片墙跟着资料走、动态跟着一条可删的内容走），
     * 合并后就没法只清某一侧的字节。
     *
     * <p>★ 开 {@link #isPublic()} 的理由与 {@link #DATING_PHOTO} 完全同构：动态流要在
     * {@code <img>} 里渲染<b>别人</b>的图，逐张校验关系既做不到也没意义；
     * 「谁能看到这一条」由 {@code im_moment.visibility} 与白名单在 im-friend 服务层判定，
     * 判定不通过时那一行的地址根本不下发。因此这一类的 {@code fileId} <b>只能从动态接口里下发</b>。
     */
    MOMENT_IMAGE("moment_image", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jfif")),

    /**
     * 交友动态的附视频（一条最多 1 个，与图片互斥）。
     *
     * <p>与 {@link #VIDEO} 分开：那是直播页视频板块的作品，面向全部登录用户的信息流；
     * 这是动态里的一段随手拍，受众恒为作者的好友与配对对象。
     * 开 {@link #isPublic()} 的理由同 {@link #MOMENT_IMAGE}：渲染方是别人，鉴权在业务侧。
     */
    MOMENT_VIDEO("moment_video", Set.of("mp4", "avi", "mov", "mkv", "webm", "flv", "wmv")),

    /**
     * 客户端安装包（后台「客户端版本管理」上传的安装包）。官网下载页递给匿名访客的就是
     * 这一类的字节，白名单只列安装器与归档容器：exe / msi 是 Windows 双包、zip 是免安装版、
     * apk 是安卓包，dmg / pkg / deb / rpm / appx / msix / tar / gz / tgz 留给其余分发形态。
     *
     * <p>与 {@link #CHAT_FILE} 里的安装包扩展名看似重复，刻意不合并：聊天里传的安装包是
     * 两个人之间的私有字节，而这一类是「后台启用后全网匿名可下载」的公开产物，暴露面不同，
     * 归档与清理口径也该各自独立。下载出口不走 {@code /api/file/download} 而由发布端点自己
     * 流式输出（启用状态即鉴权），所以不开 {@code isPublic()}——那一项只作用于
     * {@code canAccess} 的会话校验豁免，与本类的公开方式无关。
     */
    CLIENT_RELEASE("client_release", Set.of(
            "exe", "msi", "zip", "apk", "dmg", "pkg", "deb", "rpm", "appx", "msix", "tar", "gz", "tgz"));

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
     *
     * <p>三板块的四类都<b>不进这里</b>（与 {@link #VIDEO_COVER}、{@link #ARTICLE_COVER} 同口径）：
     * 它们的字节是从 {@code /api/file/download} 出去的，而前端统一用带认证头的请求取 blob
     * 再交给 {@code <img>} / {@code <audio>} 渲染，Content-Disposition 是 attachment 也不影响播放；
     * 反过来一旦归进 isImage，就等于允许任何人拿直链在同源窗口里直接渲染。
     */
    public boolean isImage() {
        return this == AVATAR || this == CHAT_IMAGE;
    }

    /**
     * 是否为公开资源：头像、视频板块的视频与封面都属于「非上传者、非同一会话也应当能看」的内容。
     * {@code FileServiceImpl#canAccess} 据此放行，不再走会话可见性判定。
     *
     * <p>三板块的四类都算公开：图片、音频、封面都是信息流卡片要替<b>别人</b>的作品渲染的字节，
     * 逐条校验作者关系既做不到也没意义（与 {@link #VIDEO} 同一理由）。
     */
    public boolean isPublic() {
        return this == AVATAR || this == VIDEO || this == VIDEO_COVER
                || this == ARTICLE_COVER || this == ARTICLE_IMG
                || this == IMAGE_WORK || this == MUSIC
                || this == MUSIC_COVER || this == NOVEL_COVER
                || this == DATING_PHOTO
                || this == MOMENT_IMAGE || this == MOMENT_VIDEO;
    }
}
