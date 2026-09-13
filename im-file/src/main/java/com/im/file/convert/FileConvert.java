package com.im.file.convert;

import com.im.common.domain.FileDTO;
import com.im.common.util.TextUtil;
import com.im.file.dto.vo.FileVO;
import com.im.file.entity.FileEntity;
import com.im.file.enums.FileBizType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件对象转换与命名工具。
 *
 * <p>用静态方法而不是 MapStruct：转换规则里全是「哪个字段该给谁看」的判断
 * （受控地址可以落库、票据地址不行），这类语义映射器只会把它藏进生成代码里。
 */
public final class FileConvert {

    /** 原始文件名列宽 255，留出余量按 200 截断 */
    private static final int MAX_NAME_LENGTH = 200;

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String[] SIZE_UNITS = {"KB", "MB", "GB", "TB"};

    /**
     * 扩展名到 MIME 的映射，下载时一律以它为准。
     *
     * <p>不用客户端上报的 {@code Content-Type}：那个值完全由上传者控制，
     * 把一个 {@code .png} 标成 {@code text/html} 再从本站同源 inline 返回，就是一次存储型 XSS。
     * 表里没有的类型一律归为 {@code application/octet-stream}，宁可让浏览器弹下载框，
     * 也不能让它在本站的源里渲染任何未知内容。
     */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("aac", "audio/aac"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("amr", "audio/amr"),
            Map.entry("flac", "audio/flac"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("mkv", "video/x-matroska"),
            Map.entry("flv", "video/x-flv"),
            Map.entry("wmv", "video/x-ms-wmv"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("json", "application/json"),
            // md / csv / log 归到纯文本：它们本质是文本，但绝不能按 text/html 渲染
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/plain"),
            Map.entry("csv", "text/plain"),
            Map.entry("log", "text/plain"));

    /** 未知类型的兜底 MIME */
    public static final String OCTET_STREAM = "application/octet-stream";

    private FileConvert() {
    }

    /**
     * 生成对象键：{@code yyyy/MM/dd/{uuid}.{ext}}。
     *
     * <p>按天分目录是因为单个平铺目录撑到几十万文件之后，无论 ext4 还是对象存储的列举操作都会明显变慢；
     * 文件名用 UUID 而不是原始名，是为了让「同名文件互相覆盖」与「原始名里的特殊字符」这两类问题一次性消失。
     */
    public static String newObjectKey(String ext) {
        String prefix = LocalDate.now().format(DATE_PATH) + "/" + UUID.randomUUID().toString().replace("-", "");
        return TextUtil.isBlank(ext) ? prefix : prefix + "." + ext;
    }

    /**
     * 清理客户端上报的文件名。
     *
     * <p>三件事必须做：剥掉老浏览器会带上的完整路径、去掉能污染 HTTP 头的控制字符、
     * 去掉能提前闭合 {@code Content-Disposition} 的引号。文件名是唯一一个「客户端可控、
     * 又会被原样拼进响应头」的字段，这里不洗干净就没有别的地方会洗。
     *
     * @return 清理后为空时返回 {@code null}
     */
    public static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\x00-\\x1F\\x7F\"']", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /**
     * 下载时用的文件名，缺原始名时退回对象键的最后一段，保证响应头永远有一个可用的名字。
     */
    public static String downloadName(FileEntity file) {
        String name = sanitizeFileName(file.getOriginalName());
        if (name != null) {
            return name;
        }
        String objectKey = file.getObjectKey();
        int slash = objectKey == null ? -1 : objectKey.lastIndexOf('/');
        return slash >= 0 ? objectKey.substring(slash + 1) : "file";
    }

    /**
     * 按扩展名解析出可安全返回的 MIME 类型，未知类型一律降级为二进制流。
     */
    public static String safeContentType(String ext) {
        if (TextUtil.isBlank(ext)) {
            return OCTET_STREAM;
        }
        return CONTENT_TYPES.getOrDefault(ext.toLowerCase(Locale.ROOT), OCTET_STREAM);
    }

    /**
     * 下载响应该用的 MIME：一律按扩展名重新解析，连元数据里存的值也不采信。
     *
     * <p>{@code content_type} 只是个 VARCHAR，一条 SQL 就能往里塞 {@code text/html}，
     * 而这个值会被原样写进响应头；扩展名则来自上传时的白名单校验，可信度高得多。
     */
    public static String responseContentType(FileEntity file) {
        return safeContentType(file.getExt());
    }

    /**
     * 是否优先在浏览器里直接渲染（图片、视频）而不是弹下载框。
     *
     * <p>视频与图片同理：聊天场景里用户期望点开就能看，而不是下一个文件。
     * 客户端压缩后的视频统一为 MP4 (H.264+AAC)，浏览器原生支持 inline 播放。
     */
    public static boolean inline(FileEntity file) {
        FileBizType bizType = FileBizType.of(file.getBizType());
        if (bizType == null) {
            return false;
        }
        if (bizType.isImage()) {
            return true;
        }
        // 视频类型也走 inline：浏览器 <video> 标签需要直接拿到字节流
        String ext = file.getExt();
        return ext != null && VIDEO_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** 支持 inline 播放的视频扩展名 */
    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "webm", "ogg", "mov", "avi", "mkv", "flv", "wmv");

    /**
     * 人类可读的文件大小。
     */
    public static String sizeText(Long size) {
        if (size == null || size <= 0) {
            return "0 B";
        }
        if (size < 1024) {
            return size + " B";
        }
        double value = size;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < SIZE_UNITS.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS[unit]);
    }

    /**
     * 转成前端视图，{@code signedUrl} 由调用方按当前请求者签发。
     */
    public static FileVO toVO(FileEntity file, String signedUrl) {
        FileBizType bizType = FileBizType.of(file.getBizType());
        return FileVO.builder()
                .fileId(file.getId())
                .uploaderId(file.getUploaderId())
                .bizType(file.getBizType())
                .originalName(file.getOriginalName())
                .url(file.getUrl())
                .signedUrl(signedUrl)
                .size(file.getSize())
                .sizeText(sizeText(file.getSize()))
                .contentType(file.getContentType())
                .ext(file.getExt())
                .md5(file.getMd5())
                .duration(file.getDuration())
                .image(bizType != null && bizType.isImage())
                .createTime(file.getCreateTime())
                .build();
    }

    /**
     * 转成跨模块传输对象。
     *
     * <p>{@code url} 一律填长期有效的受控地址：这个 DTO 会被消息模块写进 {@code extra.fileUrl}
     * 长期保存，带票据的地址半小时后就失效，聊天记录里的图片会集体变成裂图。
     *
     * <p>{@code thumbnailUrl} 恒为空——本项目不做服务端缩略图，前端用 CSS 缩放原图；
     * 真要做也应该由独立的异步任务生成后回填，而不是在上传请求里同步阻塞。
     */
    public static FileDTO toDTO(FileEntity file) {
        return FileDTO.builder()
                .fileId(file.getId())
                .uploaderId(file.getUploaderId())
                .bizType(file.getBizType())
                .storageType(file.getStorageType())
                .bucket(file.getBucket())
                .objectKey(file.getObjectKey())
                .originalName(file.getOriginalName())
                .url(file.getUrl())
                .size(file.getSize())
                .contentType(file.getContentType())
                .ext(file.getExt())
                .md5(file.getMd5())
                .duration(file.getDuration())
                .createTime(file.getCreateTime())
                .build();
    }
}
