package com.im.file.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.Result;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.domain.UploadCmd;
import com.im.common.exception.BusinessException;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.file.convert.FileConvert;
import com.im.file.dto.req.UploadInitReq;
import com.im.file.dto.vo.FileVO;
import com.im.file.dto.vo.UploadChunkVO;
import com.im.file.dto.vo.UploadInitVO;
import com.im.file.entity.FileEntity;
import com.im.file.enums.FileBizType;
import com.im.file.service.ChunkUploadService;
import com.im.file.service.FileService;
import com.im.file.service.FileTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件接口。
 *
 * <p>类上刻意没有 {@code @SaCheckLogin}：{@code /download/{id}} 必须能在没有登录头的情况下工作。
 * 浏览器渲染 {@code <img src>}、{@code <audio src>} 时不会带上任何自定义请求头，
 * 而本项目又关掉了 Sa-Token 的 Cookie 读取来规避 CSRF，于是图片只能靠 URL 上的短时票据认证。
 * 其余端点逐个标注 {@code @SaCheckLogin}，认证要求写在每个方法上而不是靠类级默认值兜底。
 *
 * <p>上传类端点全部标了按用户的 {@code @RateLimit}：上传是全站写磁盘/对象存储成本最高的操作，
 * 不限频的话一个登录用户就能用脚本把存储刷爆。分片端点配额单独放宽：
 * 2GB 文件按 5MB 切片就有 400 个分片，配额必须容得下一次完整的大文件上传。
 *
 * <p>下载之所以直接写 {@link HttpServletResponse} 而不是返回 {@code ResponseEntity<Resource>}：
 * 流的关闭必须自己握着。鉴权全部发生在写第一个字节之前，因此失败时响应还没提交，
 * 全局异常处理器照样能返回标准的 JSON 错误体。
 */
@Slf4j
@Tag(name = "06-文件", description = "上传、头像、元数据、临时访问地址与受控下载")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final ChunkUploadService chunkUploadService;
    private final FileTicketService ticketService;
    private final ImProperties imProperties;

    @Operation(summary = "上传文件", description = "bizType 可选 avatar / chat_image / chat_file / chat_voice，缺省按 chat_file 处理")
    @SaCheckLogin
    @SaCheckPermission(ImConstants.PERM_FILE_UPLOAD)
    @RateLimit(count = 30, dimension = RateLimit.Dimension.USER, key = "file.upload")
    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestPart("file") MultipartFile file,
                                 @Parameter(description = "业务类型") @RequestParam(value = "bizType", required = false) String bizType,
                                 @Parameter(description = "语音时长（秒），仅语音文件需要") @RequestParam(value = "duration", required = false) Integer duration) {
        Long userId = SecurityUtil.getUserId();
        FileEntity stored = fileService.store(toCmd(userId, file, bizType, duration));
        return Result.ok(fileService.toView(stored, userId), "上传成功");
    }

    @Operation(summary = "上传头像", description = "上传成功后同步写入当前用户的资料，无需再调一次修改资料接口")
    @SaCheckLogin
    @RateLimit(count = 10, dimension = RateLimit.Dimension.USER, key = "file.avatar")
    @PostMapping("/avatar")
    public Result<FileVO> avatar(@RequestPart("file") MultipartFile file) {
        Long userId = SecurityUtil.getUserId();
        FileEntity stored = fileService.store(toCmd(userId, file, FileBizType.AVATAR.getCode(), null));
        return Result.ok(fileService.toView(stored, userId), "头像已更新");
    }

    @Operation(summary = "分片上传初始化", description = "上报整文件 MD5：命中秒传直接返回文件信息（uploaded=true），否则下发分片大小与会话 ID 用于断点续传")
    @SaCheckLogin
    @SaCheckPermission(ImConstants.PERM_FILE_UPLOAD)
    @RateLimit(count = 30, dimension = RateLimit.Dimension.USER, key = "file.upload.init")
    @PostMapping("/upload/init")
    public Result<UploadInitVO> initUpload(@Valid @RequestBody UploadInitReq req) {
        return Result.ok(chunkUploadService.init(SecurityUtil.getUserId(), req));
    }

    @Operation(summary = "上传分片", description = "逐片上传，重复投递同一分片幂等；返回服务端当前已收到的分片下标")
    @SaCheckLogin
    @SaCheckPermission(ImConstants.PERM_FILE_UPLOAD)
    @RateLimit(count = 1200, dimension = RateLimit.Dimension.USER, key = "file.upload.chunk")
    @PostMapping("/upload/chunk")
    public Result<UploadChunkVO> uploadChunk(@Parameter(description = "init 下发的会话 ID") @RequestParam("uploadId") String uploadId,
                                             @Parameter(description = "分片下标，从 0 开始") @RequestParam("chunkIndex") int chunkIndex,
                                             @RequestPart("chunk") MultipartFile chunk) {
        return Result.ok(chunkUploadService.storeChunk(SecurityUtil.getUserId(), uploadId, chunkIndex, chunk));
    }

    @Operation(summary = "合并分片", description = "全部分片到位后合并落库，服务端重算 MD5 与大小并核对，成功后返回文件信息")
    @SaCheckLogin
    @SaCheckPermission(ImConstants.PERM_FILE_UPLOAD)
    @RateLimit(count = 30, dimension = RateLimit.Dimension.USER, key = "file.upload.merge")
    @PostMapping("/upload/merge")
    public Result<FileVO> mergeUpload(@Parameter(description = "init 下发的会话 ID") @RequestParam("uploadId") String uploadId) {
        Long userId = SecurityUtil.getUserId();
        FileEntity merged = chunkUploadService.merge(userId, uploadId);
        return Result.ok(fileService.toView(merged, userId), "上传成功");
    }

    @Operation(summary = "文件元数据", description = "需要登录且对该文件有访问权")
    @SaCheckLogin
    @GetMapping("/{id}")
    public Result<FileVO> detail(@Parameter(description = "文件 ID") @PathVariable("id") Long id) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(fileService.toView(fileService.requireAccessible(userId, id), userId));
    }

    @Operation(summary = "获取临时访问地址", description = "返回可直接用于 img src 的地址；MinIO 开启预签名时为对象存储直链")
    @SaCheckLogin
    @GetMapping("/{id}/url")
    public Result<String> temporaryUrl(@Parameter(description = "文件 ID") @PathVariable("id") Long id) {
        Long userId = SecurityUtil.getUserId();
        long ttl = imProperties.getJwt().getFileTicketTtlSeconds();
        return Result.ok(fileService.signedUrl(userId, id, ttl));
    }

    @Operation(summary = "下载文件", description = "支持 satoken 登录头或 URL 上的 ticket 票据，两者都没有时返回 401；带 If-None-Match 且指纹命中时返回 304")
    @GetMapping("/download/{id}")
    public void download(@Parameter(description = "文件 ID") @PathVariable("id") Long id,
                         @Parameter(description = "短时访问票据，img src 场景必填") @RequestParam(value = "ticket", required = false) String ticket,
                         @Parameter(description = "是否强制在浏览器内渲染，缺省按文件类型决定") @RequestParam(value = "inline", required = false) Boolean inline,
                         @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                         HttpServletResponse response) throws IOException {
        Long viewerId = resolveViewer(ticket, id);
        FileEntity file = fileService.requireAccessible(viewerId, id);
        String etag = buildETag(file);
        if (etag.equals(ifNoneMatch)) {
            // 协商缓存命中：内容没变，不吐字节体，客户端据 ETag 继续用本地媒体缓存
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            response.setHeader(HttpHeaders.ETAG, etag);
            return;
        }
        write(file, inline, response, etag);
    }

    /**
     * 资源指纹：文件 ID + 内容 MD5（MD5 缺失时退化到更新时间）。
     *
     * <p>客户端把指纹随媒体缓存一起存下，再次请求时带 If-None-Match：
     * 内容未变回 304 省流量，内容变了会拿到新 ETag，客户端据此让本地缓存失效并重拉。
     * 用弱指纹（W/ 前缀）：不保证跨网关/转码后字节级一致，语义上只需可比对性。
     */
    private String buildETag(FileEntity file) {
        String version = TextUtil.isNotBlank(file.getMd5())
                ? file.getMd5()
                : String.valueOf(file.getUpdateTime() == null ? 0L
                        : file.getUpdateTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
        return "W/\"" + file.getId() + "-" + version + "\"";
    }

    /**
     * 解析下载请求的身份。
     *
     * <p>票据优先于登录头：前端拿到的是一个完整的可直接访问的 URL，
     * 它可能来自另一台设备的分享，此时登录态属于谁并不重要，票据里写的才是被授权的那个人。
     * 两条路都走不通时返回 401 而不是 403——「不知道你是谁」与「知道你是谁但不给」要分开。
     */
    private Long resolveViewer(String ticket, Long fileId) {
        if (TextUtil.isNotBlank(ticket)) {
            return ticketService.verify(ticket, fileId);
        }
        Long loginId = SecurityUtil.getUserIdOrNull();
        BusinessException.throwIf(loginId == null, ResultCode.UNAUTHORIZED);
        return loginId;
    }

    /**
     * 把 {@link MultipartFile} 搬进跨模块的 {@link UploadCmd}。
     *
     * <p>{@code getBytes} 会把整个文件读进内存，上限由 {@code im.file.max-size} 与
     * {@code spring.servlet.multipart.max-file-size} 双重兜住；这里不做大小判断，
     * 让服务层按 bizType 决定用哪一条上限，避免两处规则各自演化。
     */
    private UploadCmd toCmd(Long userId, MultipartFile file, String bizType, Integer duration) {
        BusinessException.throwIf(file == null || file.isEmpty(), ResultCode.FILE_EMPTY);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("[文件] 读取上传内容失败: name={}, size={}", file.getOriginalFilename(), file.getSize(), e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        return UploadCmd.builder()
                .uploaderId(userId)
                .bizType(TextUtil.isBlank(bizType) ? FileBizType.CHAT_FILE.getCode() : bizType)
                .originalName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size((long) bytes.length)
                .duration(duration)
                .bytes(bytes)
                .build();
    }

    /**
     * 写响应头并流式输出内容。
     *
     * <p>文件名交给 {@link ContentDisposition} 按 RFC 5987 编码，中文文件名才会被浏览器正确还原；
     * {@code nosniff} 是内容类型收敛的最后一道保险——扩展名白名单挡住了上传，
     * 这一行挡住浏览器自己「猜」出别的类型来渲染。
     */
    private void write(FileEntity file, Boolean inline, HttpServletResponse response, String etag) throws IOException {
        boolean renderInline = inline != null ? inline : FileConvert.inline(file);
        ContentDisposition disposition = (renderInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(FileConvert.downloadName(file), StandardCharsets.UTF_8)
                .build();
        response.setContentType(FileConvert.responseContentType(file));
        if (file.getSize() != null && file.getSize() > 0) {
            response.setContentLengthLong(file.getSize());
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.ETAG, etag);
        try (InputStream in = fileService.openStream(file)) {
            StreamUtils.copy(in, response.getOutputStream());
        }
        response.flushBuffer();
    }
}
