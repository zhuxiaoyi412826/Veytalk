package com.im.file.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import com.im.file.convert.FileConvert;
import com.im.file.dto.po.MergedUpload;
import com.im.file.dto.req.UploadInitReq;
import com.im.file.dto.vo.UploadChunkVO;
import com.im.file.dto.vo.UploadInitVO;
import com.im.file.entity.FileEntity;
import com.im.file.enums.FileBizType;
import com.im.file.service.ChunkUploadService;
import com.im.file.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 分片上传实现。
 *
 * <p><b>会话标识</b>：{@code uploadId = userId + "-" + md5}，由服务端在 init 时按上传者与整文件 MD5
 * 推导，而不是随机发一个。这样同一个人重复上传同一个文件天然落到同一个会话目录——
 * 断点续传不需要额外的「按 MD5 找会话」的索引，进程重启后凭 uploadId 就能接着传。
 * 由于 uploadId 内嵌了 userId，且每个写操作都会核对 meta 里的 uploaderId，别人拿到这个 ID 也无法操作。
 *
 * <p><b>目录结构</b>：{@code {tmpDir}/{uploadId}/} 下放一个 {@code meta} 文本文件与若干
 * {@code {index}.part} 分片。meta 用简单的 {@code key=value} 行格式，不引 JSON 库，
 * 免得和项目的 Jackson 2/3 双版本约定纠缠。
 *
 * <p><b>落盘策略</b>：分片先写 {@code .tmp} 再原子改名，保证任何时刻 {@code .part} 都是完整的，
 * 并发或重传同一分片不会读到半截数据；合并阶段先只读地重算 MD5 与大小、核对通过后再流式写入存储，
 * 校验不通过时根本不会产生任何对象，也就不需要事后删除。
 */
@Slf4j
@Service
public class ChunkUploadServiceImpl implements ChunkUploadService {

    /** 合法 md5：32 位十六进制 */
    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");

    /** 合法 uploadId：{数字 userId}-{32 位 md5}，同时兼作目录名的越界防护 */
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^\\d+-[a-fA-F0-9]{32}$");

    private static final String META_NAME = "meta";
    private static final String PART_SUFFIX = ".part";
    private static final String TMP_SUFFIX = ".tmp";

    private static final String K_UPLOADER = "uploaderId";
    private static final String K_MD5 = "md5";
    private static final String K_SIZE = "size";
    private static final String K_BIZ_TYPE = "bizType";
    private static final String K_NAME = "originalName";
    private static final String K_DURATION = "duration";
    private static final String K_CHUNK_SIZE = "chunkSize";
    private static final String K_TOTAL = "totalChunks";
    private static final String K_CREATE_TIME = "createTime";

    private final ImProperties imProperties;
    private final FileService fileService;

    private final Path tmpRoot;

    public ChunkUploadServiceImpl(ImProperties imProperties, FileService fileService) {
        this.imProperties = imProperties;
        this.fileService = fileService;
        this.tmpRoot = Paths.get(imProperties.getFile().getUpload().getTmpDir()).toAbsolutePath().normalize();
    }

    /* ==================== 初始化 ==================== */

    @Override
    public UploadInitVO init(Long userId, UploadInitReq req) {
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED);
        BusinessException.throwIf(req == null, ResultCode.BAD_REQUEST, "上传参数不能为空");

        String md5 = req.getMd5() == null ? null : req.getMd5().trim().toLowerCase();
        BusinessException.throwUnless(md5 != null && MD5_PATTERN.matcher(md5).matches(),
                ResultCode.BAD_REQUEST, "md5 格式非法");
        long size = req.getSize() == null ? 0L : req.getSize();
        ImProperties.File.Upload cfg = imProperties.getFile().getUpload();
        BusinessException.throwIf(size <= 0, ResultCode.FILE_EMPTY);
        BusinessException.throwIf(size > cfg.getMaxSize().toBytes(), ResultCode.UPLOAD_TOO_LARGE);

        // 业务类型在 init 阶段就解析成枚举 code 再落 meta：既做白名单前置校验，
        // 也避免把客户端可能带的换行/控制字符写进 key=value 格式的 meta 而破坏它；
        // 文件名同理走 sanitizeFileName（剔掉 \x00-\x1F 等控制字符）。
        FileBizType bizType = FileBizType.of(TextUtil.isBlank(req.getBizType())
                ? FileBizType.CHAT_FILE.getCode() : req.getBizType());
        BusinessException.throwIf(bizType == null, ResultCode.BAD_REQUEST, "未知的文件业务类型：" + req.getBizType());
        String safeName = FileConvert.sanitizeFileName(req.getOriginalName());

        // 1) 秒传：已有相同内容的对象就直接为当前用户建记录，一个字节的上传都省掉
        FileEntity reused = fileService.instantReuse(userId, bizType.getCode(), safeName,
                md5, size, req.getDuration());
        if (reused != null) {
            log.info("[分片上传] 秒传命中，无需上传: md5={}, size={}, uploader={}", md5, size, userId);
            return UploadInitVO.builder()
                    .uploaded(true)
                    .file(fileService.toView(reused, userId))
                    .build();
        }

        // 2) 需要真正上传：按权威分片大小算出分片总数，建立或复用会话
        long chunkSize = cfg.getChunkSize().toBytes();
        int totalChunks = (int) ((size + chunkSize - 1) / chunkSize);
        BusinessException.throwIf(totalChunks < 1 || totalChunks > cfg.getMaxChunks(),
                ResultCode.FILE_CHUNK_INVALID, "分片数非法：" + totalChunks);

        String uploadId = userId + "-" + md5;
        Path dir = sessionDir(uploadId);
        try {
            Files.createDirectories(dir);
            Map<String, String> meta = readMeta(dir);
            if (meta == null) {
                meta = new HashMap<>();
                meta.put(K_UPLOADER, String.valueOf(userId));
                meta.put(K_MD5, md5);
                meta.put(K_SIZE, String.valueOf(size));
                meta.put(K_BIZ_TYPE, bizType.getCode());
                meta.put(K_NAME, safeName == null ? "" : safeName);
                meta.put(K_DURATION, req.getDuration() == null ? "" : String.valueOf(req.getDuration()));
                meta.put(K_CHUNK_SIZE, String.valueOf(chunkSize));
                meta.put(K_TOTAL, String.valueOf(totalChunks));
                meta.put(K_CREATE_TIME, String.valueOf(System.currentTimeMillis()));
                writeMeta(dir, meta);
            } else {
                // 复用旧会话前核对关键参数：md5/size/uploader 对不上说明是不同文件撞了同一个 ID，
                // 直接拒绝，避免把别人的分片和这次声明拼在一起
                BusinessException.throwUnless(
                        md5.equals(meta.get(K_MD5))
                                && size == parseLong(meta.get(K_SIZE), -1L)
                                && userId.toString().equals(meta.get(K_UPLOADER)),
                        ResultCode.FILE_CHUNK_INVALID, "上传会话与当前文件不匹配，请重新发起");
                totalChunks = (int) parseLong(meta.get(K_TOTAL), totalChunks);
                chunkSize = parseLong(meta.get(K_CHUNK_SIZE), chunkSize);
            }
            List<Integer> uploadedChunks = listChunks(dir, totalChunks);
            log.info("[分片上传] 会话就绪: uploadId={}, totalChunks={}, 已收={}, uploader={}",
                    uploadId, totalChunks, uploadedChunks.size(), userId);
            return UploadInitVO.builder()
                    .uploaded(false)
                    .uploadId(uploadId)
                    .chunkSize(chunkSize)
                    .totalChunks(totalChunks)
                    .uploadedChunks(uploadedChunks)
                    .build();
        } catch (IOException e) {
            log.error("[分片上传] 初始化会话失败: uploadId={}", uploadId, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /* ==================== 分片落盘 ==================== */

    @Override
    public UploadChunkVO storeChunk(Long userId, String uploadId, int chunkIndex, MultipartFile part) {
        Map<String, String> meta = requireOwnedSession(userId, uploadId);
        int totalChunks = (int) parseLong(meta.get(K_TOTAL), -1L);
        BusinessException.throwIf(chunkIndex < 0 || chunkIndex >= totalChunks,
                ResultCode.FILE_CHUNK_INVALID, "分片下标越界：" + chunkIndex);
        BusinessException.throwIf(part == null || part.isEmpty(), ResultCode.FILE_EMPTY);

        long maxChunk = imProperties.getFile().getUpload().getMaxChunkSize().toBytes();
        BusinessException.throwIf(part.getSize() > maxChunk, ResultCode.UPLOAD_TOO_LARGE);

        Path dir = sessionDir(uploadId);
        Path target = dir.resolve(chunkIndex + PART_SUFFIX);
        Path tmp = dir.resolve(chunkIndex + PART_SUFFIX + TMP_SUFFIX);
        try {
            part.transferTo(tmp);
            // 原子改名：任何时刻 .part 都是完整分片，重传/并发不会读到半截
            atomicMove(tmp, target);
        } catch (IOException e) {
            deleteQuietly(tmp);
            log.error("[分片上传] 分片写入失败: uploadId={}, index={}", uploadId, chunkIndex, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        return UploadChunkVO.builder()
                .chunkIndex(chunkIndex)
                .uploadedChunks(listChunks(dir, totalChunks))
                .build();
    }

    /* ==================== 合并落库 ==================== */

    @Override
    public FileEntity merge(Long userId, String uploadId) {
        Map<String, String> meta = requireOwnedSession(userId, uploadId);
        Path dir = sessionDir(uploadId);
        int totalChunks = (int) parseLong(meta.get(K_TOTAL), -1L);
        long declaredSize = parseLong(meta.get(K_SIZE), -1L);
        String declaredMd5 = meta.get(K_MD5);

        // 完整性：所有分片都必须到位
        for (int i = 0; i < totalChunks; i++) {
            BusinessException.throwUnless(Files.isRegularFile(dir.resolve(i + PART_SUFFIX)),
                    ResultCode.FILE_CHUNK_INVALID, "缺少分片 " + i);
        }

        // 只读地重算 md5 与大小并与声明值核对：不通过就不写任何对象，无需事后清理
        byte[] buffer = new byte[8192];
        long actualSize = 0L;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法，理论上不会发生
            throw new IllegalStateException("MD5 算法不可用", e);
        }
        for (int i = 0; i < totalChunks; i++) {
            try (InputStream in = Files.newInputStream(dir.resolve(i + PART_SUFFIX))) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                    actualSize += n;
                }
            } catch (IOException e) {
                log.error("[分片上传] 合并时读取分片失败: uploadId={}, index={}", uploadId, i, e);
                throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
            }
        }
        String actualMd5 = HexFormat.of().formatHex(digest.digest());
        BusinessException.throwUnless(actualSize == declaredSize,
                ResultCode.FILE_CHUNK_INVALID, "分片总大小与声明不符");
        BusinessException.throwUnless(actualMd5.equalsIgnoreCase(declaredMd5), ResultCode.FILE_MD5_MISMATCH);

        MergedUpload merged = MergedUpload.builder()
                .uploaderId(userId)
                .bizType(meta.get(K_BIZ_TYPE))
                .originalName(meta.get(K_NAME))
                .size(actualSize)
                .md5(actualMd5.toLowerCase())
                .duration(parseDuration(meta.get(K_DURATION)))
                // 每次调用返回一个从头读取的合并流；命中秒传时 storeMerged 不会打开它
                .streamSupplier(() -> openMergedStream(dir, totalChunks))
                .build();

        FileEntity entity = fileService.storeMerged(merged);
        deleteRecursively(dir);
        log.info("[分片上传] 合并完成: uploadId={}, fileId={}, size={}, uploader={}",
                uploadId, entity.getId(), actualSize, userId);
        return entity;
    }

    /**
     * 打开一个把所有分片按序拼接起来的流。用 {@link SequenceInputStream} 串起各分片的文件流，
     * 它会在读完每一段后自动关闭对应的流，调用方只需关闭返回的这一个流。
     */
    private InputStream openMergedStream(Path dir, int totalChunks) {
        List<InputStream> streams = new ArrayList<>(totalChunks);
        try {
            for (int i = 0; i < totalChunks; i++) {
                streams.add(Files.newInputStream(dir.resolve(i + PART_SUFFIX)));
            }
        } catch (IOException e) {
            // 打开到一半失败，已打开的要关掉，避免句柄泄漏
            streams.forEach(this::closeQuietly);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
        Iterator<InputStream> it = streams.iterator();
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return it.hasNext();
            }

            @Override
            public InputStream nextElement() {
                return it.next();
            }
        });
    }

    /* ==================== 过期会话清理 ==================== */

    /**
     * 定时回收过期会话：用户传了一半就再也不回来的分片会一直占着磁盘，
     * 超过 {@code im.file.upload.session-ttl-seconds} 未更新的会话目录整个删掉。
     */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    public void cleanExpiredSessions() {
        if (!Files.isDirectory(tmpRoot)) {
            return;
        }
        long ttlMillis = imProperties.getFile().getUpload().getSessionTtlSeconds() * 1000L;
        long now = System.currentTimeMillis();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(tmpRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                long createTime = createTimeOf(dir);
                if (now - createTime > ttlMillis) {
                    log.info("[分片上传] 回收过期会话: dir={}", dir.getFileName());
                    deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            log.error("[分片上传] 清理过期会话失败: tmpRoot={}", tmpRoot, e);
        }
    }

    private long createTimeOf(Path dir) {
        Map<String, String> meta = readMetaQuietly(dir);
        if (meta != null) {
            long t = parseLong(meta.get(K_CREATE_TIME), -1L);
            if (t > 0) {
                return t;
            }
        }
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /* ==================== 内部工具 ==================== */

    /**
     * 校验 uploadId 格式、会话存在且归当前用户所有，返回其 meta。
     *
     * <p>uploadId 会拼进文件路径，先用正则锁死成「数字-十六进制」，杜绝 {@code ../} 越界；
     * 归属校验则防止一个用户操作另一个用户的会话。任一不满足都回「会话不存在」，
     * 不向调用方泄露「ID 格式对但文件不存在」与「ID 是别人的」之间的差别。
     */
    private Map<String, String> requireOwnedSession(Long userId, String uploadId) {
        BusinessException.throwIf(userId == null, ResultCode.UNAUTHORIZED);
        BusinessException.throwUnless(uploadId != null && UPLOAD_ID_PATTERN.matcher(uploadId).matches(),
                ResultCode.FILE_UPLOAD_SESSION_NOT_FOUND);
        Path dir = sessionDir(uploadId);
        Map<String, String> meta = readMetaQuietly(dir);
        BusinessException.throwIf(meta == null, ResultCode.FILE_UPLOAD_SESSION_NOT_FOUND);
        BusinessException.throwUnless(userId.toString().equals(meta.get(K_UPLOADER)),
                ResultCode.FILE_UPLOAD_SESSION_NOT_FOUND);
        return meta;
    }

    /** uploadId 已在 {@link #requireOwnedSession} 里通过正则校验，这里拼路径是安全的。 */
    private Path sessionDir(String uploadId) {
        return tmpRoot.resolve(uploadId).normalize();
    }

    private List<Integer> listChunks(Path dir, int totalChunks) {
        List<Integer> chunks = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            if (Files.isRegularFile(dir.resolve(i + PART_SUFFIX))) {
                chunks.add(i);
            }
        }
        return chunks;
    }

    private Map<String, String> readMeta(Path dir) throws IOException {
        Path metaFile = dir.resolve(META_NAME);
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
        for (String line : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return map;
    }

    private Map<String, String> readMetaQuietly(Path dir) {
        try {
            return readMeta(dir);
        } catch (IOException e) {
            log.warn("[分片上传] 读取会话 meta 失败: dir={}", dir, e);
            return null;
        }
    }

    private void writeMeta(Path dir, Map<String, String> meta) throws IOException {
        StringBuilder sb = new StringBuilder();
        meta.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        Path tmp = dir.resolve(META_NAME + TMP_SUFFIX);
        Path target = dir.resolve(META_NAME);
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        atomicMove(tmp, target);
    }

    /**
     * 同目录原子改名。优先用 {@code ATOMIC_MOVE} 保证读者看不到半截文件；
     * 个别文件系统（部分 Windows/网络盘）不支持原子移动时会抛
     * {@link java.nio.file.AtomicMoveNotSupportedException}，回退到普通改名。
     */
    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long parseLong(String value, long fallback) {
        if (TextUtil.isBlank(value)) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Integer parseDuration(String value) {
        long v = parseLong(value, -1L);
        return v > 0 ? (int) v : null;
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // 清理失败不影响主流程，最多残留一个待下次定时任务回收的目录
            log.warn("[分片上传] 清理临时目录失败: dir={}", dir, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删不掉无所谓，下次原子改名会覆盖
        }
    }

    private void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
