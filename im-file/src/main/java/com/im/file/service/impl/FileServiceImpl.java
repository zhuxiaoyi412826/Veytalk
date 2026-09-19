package com.im.file.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.domain.UploadCmd;
import com.im.common.enums.StorageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.UserProfileSpi;
import com.im.common.util.TextUtil;
import com.im.file.convert.FileConvert;
import com.im.file.dto.po.MergedUpload;
import com.im.file.dto.vo.FileVO;
import com.im.file.entity.FileEntity;
import com.im.file.enums.FileBizType;
import com.im.file.mapper.FileMapper;
import com.im.file.service.FileService;
import com.im.file.service.FileTicketService;
import com.im.file.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文件服务实现。
 *
 * <p>{@link #store} 刻意不加 {@code @Transactional}：写字节是一次可能持续数秒的磁盘 / 网络 I/O，
 * 把它包进事务等于在整个上传过程中占着一条数据库连接，并发上传时会先把连接池抽干，
 * 表现成「所有接口一起变慢」这种极难归因的故障。这里只写一行元数据，
 * 单条 INSERT 本身就是原子的，没有需要事务保护的多语句序列。
 *
 * <p>顺序是先写字节、再写元数据。反过来会出现「记录已存在但对象不在」的窗口，
 * 那是一条永久坏死的记录；而现在最坏只是残留一个没人引用的孤儿文件，不影响任何正确性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    /** 语音时长的合理区间，超出即视为客户端上报了脏数据 */
    private static final int MAX_VOICE_SECONDS = 24 * 60 * 60;

    private final FileMapper fileMapper;
    private final FileStorage fileStorage;
    private final FileTicketService ticketService;
    private final ImProperties imProperties;
    private final ObjectProvider<MessageSpi> messageSpiProvider;
    private final ObjectProvider<UserProfileSpi> userProfileSpiProvider;

    /* ==================== 上传 ==================== */

    @Override
    public FileEntity store(UploadCmd cmd) {
        BusinessException.throwIf(cmd == null, ResultCode.BAD_REQUEST, "上传参数不能为空");
        BusinessException.throwIf(cmd.getUploaderId() == null, ResultCode.UNAUTHORIZED);
        byte[] bytes = cmd.getBytes();
        BusinessException.throwIf(bytes == null || bytes.length == 0, ResultCode.FILE_EMPTY);

        FileBizType bizType = requireBizType(cmd.getBizType());
        String originalName = FileConvert.sanitizeFileName(cmd.getOriginalName());
        String ext = requireAllowedExt(originalName, bizType);

        // 头像与普通附件是两道独立的上限：头像会被各类列表反复拉取，没必要允许传原图
        DataSize limit = bizType == FileBizType.AVATAR
                ? imProperties.getFile().getMaxAvatarSize()
                : imProperties.getFile().getMaxSize();
        BusinessException.throwIf(bytes.length > limit.toBytes(), ResultCode.UPLOAD_TOO_LARGE);

        // 客户端上报的 md5 一律忽略：它决定复用哪一份字节，谎报一次就能让两个不同的文件指向同一个对象
        String md5 = DigestUtils.md5DigestAsHex(bytes);
        String storageType = fileStorage.type().getCode();
        String contentType = FileConvert.safeContentType(ext);

        String objectKey = findReusableObjectKey(md5, storageType, bytes.length);
        if (objectKey != null) {
            log.info("[文件] 秒传命中，跳过字节写入: md5={}, objectKey={}, uploader={}",
                    md5, objectKey, cmd.getUploaderId());
        } else {
            objectKey = FileConvert.newObjectKey(ext);
            writeBytes(bytes, contentType, objectKey);
        }

        FileEntity entity = insertRecord(cmd.getUploaderId(), bizType, originalName, ext,
                contentType, objectKey, bytes.length, md5, cmd.getDuration());
        log.info("[文件] 上传成功: fileId={}, bizType={}, size={}, uploader={}",
                entity.getId(), bizType.getCode(), bytes.length, cmd.getUploaderId());
        return entity;
    }

    @Override
    public FileEntity instantReuse(Long uploaderId, String bizTypeCode, String originalName,
                                   String md5, long size, Integer duration) {
        BusinessException.throwIf(uploaderId == null, ResultCode.UNAUTHORIZED);
        if (TextUtil.isBlank(md5) || size <= 0) {
            return null;
        }
        FileBizType bizType = requireBizType(TextUtil.isBlank(bizTypeCode) ? FileBizType.CHAT_FILE.getCode() : bizTypeCode);
        String name = FileConvert.sanitizeFileName(originalName);
        String ext = requireAllowedExt(name, bizType);
        BusinessException.throwIf(size > limitOf(bizType).toBytes(), ResultCode.UPLOAD_TOO_LARGE);

        String objectKey = findReusableObjectKey(md5, fileStorage.type().getCode(), size);
        if (objectKey == null) {
            return null;
        }
        String contentType = FileConvert.safeContentType(ext);
        FileEntity entity = insertRecord(uploaderId, bizType, name, ext, contentType, objectKey, size, md5, duration);
        log.info("[文件] 秒传命中，已建记录: fileId={}, md5={}, objectKey={}, uploader={}",
                entity.getId(), md5, objectKey, uploaderId);
        return entity;
    }

    @Override
    public FileEntity storeMerged(MergedUpload upload) {
        BusinessException.throwIf(upload == null, ResultCode.BAD_REQUEST, "上传参数不能为空");
        BusinessException.throwIf(upload.getUploaderId() == null, ResultCode.UNAUTHORIZED);
        BusinessException.throwIf(upload.getStreamSupplier() == null, ResultCode.BAD_REQUEST, "缺少内容流");

        FileBizType bizType = requireBizType(TextUtil.isBlank(upload.getBizType()) ? FileBizType.CHAT_FILE.getCode() : upload.getBizType());
        String originalName = FileConvert.sanitizeFileName(upload.getOriginalName());
        String ext = requireAllowedExt(originalName, bizType);
        long size = upload.getSize();
        BusinessException.throwIf(size <= 0, ResultCode.FILE_EMPTY);
        BusinessException.throwIf(size > limitOf(bizType).toBytes(), ResultCode.UPLOAD_TOO_LARGE);
        String md5 = upload.getMd5();
        BusinessException.throwIf(TextUtil.isBlank(md5), ResultCode.BAD_REQUEST, "md5 不能为空");

        String storageType = fileStorage.type().getCode();
        String contentType = FileConvert.safeContentType(ext);

        // 合并阶段再判一次秒传：大文件上传耗时，期间很可能已有别人传完同一份，命中就不必再写一遍
        String objectKey = findReusableObjectKey(md5, storageType, size);
        if (objectKey != null) {
            log.info("[文件] 合并阶段秒传命中，跳过写入: md5={}, objectKey={}, uploader={}",
                    md5, objectKey, upload.getUploaderId());
        } else {
            objectKey = FileConvert.newObjectKey(ext);
            try (InputStream in = upload.getStreamSupplier().get()) {
                fileStorage.upload(in, size, contentType, objectKey);
            } catch (IOException e) {
                log.error("[文件] 合并写入失败: objectKey={}", objectKey, e);
                throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
            }
        }

        FileEntity entity = insertRecord(upload.getUploaderId(), bizType, originalName, ext,
                contentType, objectKey, size, md5, upload.getDuration());
        log.info("[文件] 分片合并上传成功: fileId={}, bizType={}, size={}, uploader={}",
                entity.getId(), bizType.getCode(), size, upload.getUploaderId());
        return entity;
    }

    /**
     * 校验并返回业务类型，未知类型一律拒绝（不回退默认值，理由见 {@link FileBizType#of}）。
     */
    private FileBizType requireBizType(String code) {
        FileBizType bizType = FileBizType.of(code);
        BusinessException.throwIf(bizType == null, ResultCode.BAD_REQUEST, "未知的文件业务类型：" + code);
        return bizType;
    }

    /**
     * 取扩展名并校验白名单。没有扩展名一律拒绝：无法判断类型就等于无法判断风险。
     *
     * @param sanitizedOriginalName 已经过 {@link FileConvert#sanitizeFileName} 清理的文件名
     */
    private String requireAllowedExt(String sanitizedOriginalName, FileBizType bizType) {
        String ext = TextUtil.extension(sanitizedOriginalName);
        BusinessException.throwUnless(bizType.allows(ext), ResultCode.FILE_TYPE_NOT_ALLOWED,
                TextUtil.isBlank(ext) ? "(无扩展名)" : ext);
        return ext;
    }

    /**
     * 分片通道的大小上限：头像仍走头像上限，其余走 {@code im.file.upload.max-size}（默认 2GB）。
     *
     * <p>与普通上传的 {@code im.file.max-size}（默认 100MB）刻意分开：后者受 byte[] 与 multipart
     * 内存上限约束，而分片通道边读边写，可以承载远超内存的大文件。
     */
    private DataSize limitOf(FileBizType bizType) {
        return bizType == FileBizType.AVATAR
                ? imProperties.getFile().getMaxAvatarSize()
                : imProperties.getFile().getUpload().getMaxSize();
    }

    /**
     * 组装并插入一行文件元数据，头像类型顺带回写用户资料。
     *
     * <p>{@code store} / {@code instantReuse} / {@code storeMerged} 三条上传路径共用它，
     * 保证「主键提前取号、url 内嵌 ID、头像回写」这些细节不会在某条路径上漏掉。
     */
    private FileEntity insertRecord(Long uploaderId, FileBizType bizType, String originalName, String ext,
                                    String contentType, String objectKey, long size, String md5, Integer duration) {
        // 主键提前取号而不是等 INSERT 回填：url 列里就带着这个 ID，先拿到 ID 才能一次写完
        long fileId = IdWorker.getId();
        FileEntity entity = new FileEntity();
        entity.setId(fileId);
        entity.setUploaderId(uploaderId);
        entity.setBizType(bizType.getCode());
        entity.setStorageType(fileStorage.type().getCode());
        entity.setBucket(fileStorage.bucket());
        entity.setObjectKey(objectKey);
        entity.setOriginalName(originalName);
        entity.setUrl(accessUrl(fileId));
        entity.setSize(size);
        entity.setContentType(contentType);
        entity.setExt(ext);
        entity.setMd5(md5);
        entity.setDuration(normalizeDuration(duration));
        fileMapper.insert(entity);

        if (bizType == FileBizType.AVATAR) {
            applyAvatar(uploaderId, entity.getUrl());
        }
        return entity;
    }

    /**
     * 写字节到当前存储实现。
     */
    private void writeBytes(byte[] bytes, String contentType, String objectKey) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            fileStorage.upload(in, bytes.length, contentType, objectKey);
        } catch (IOException e) {
            // 只可能是 ByteArrayInputStream 的 close 出问题，理论上不会发生，留着是为了不把受检异常扩散出去
            log.error("[文件] 关闭输入流失败: objectKey={}", objectKey, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 按 MD5 找一份可以复用的字节，命中则不必重复写入存储。
     *
     * <p>这里不加 Redis 缓存：{@code idx_md5} 让它本来就是一次索引定位，
     * 而缓存没有失效通道——一旦有人手工清理过存储里的对象，缓存会把后来所有相同文件的上传
     * 都指向一个已经不存在的路径，且这种损坏是永久的、写入元数据之后才发现不了。
     * 省下的零点几毫秒换不来这个风险。
     *
     * <p>同理，命中后还要用 {@link FileStorage#exists} 回存储确认对象真的在：MinIO 控制台手工删除、
     * 生命周期策略回收都不会通知本服务，DB 里的 md5 记录会变成指向空气的孤儿。确认不存在时清掉该
     * md5 的全部记录并返回 {@code null}，让上传转为重新写入真实字节，而不是反复命中同一条坏记录。
     *
     * @return 没有可复用对象、或复用对象已失效时返回 {@code null}
     */
    private String findReusableObjectKey(String md5, String storageType, long size) {
        FileEntity exist = fileMapper.selectReusableByMd5(md5, storageType);
        // size 一并核对：MD5 相同但长度不同只可能是碰撞或客户端谎报，宁可当成新文件重写一遍
        if (exist == null || exist.getSize() == null || exist.getSize() != size) {
            return null;
        }
        // 复用前回存储确认源对象还在：不在了就把该 md5 的全部记录置空（它们共享同一个 objectKey，一起失效），
        // 转为重新上传，杜绝「秒传命中一条指向空气的记录、下载必炸」
        if (!fileStorage.exists(exist.getObjectKey())) {
            int cleared = fileMapper.clearReusableMd5(md5, storageType);
            log.warn("[文件] 秒传源对象已不存在，清除失效 md5 转为重新上传: md5={}, objectKey={}, 清除行数={}",
                    md5, exist.getObjectKey(), cleared);
            return null;
        }
        return exist.getObjectKey();
    }

    /**
     * 语音时长越界时直接丢弃而不是报错：它是展示用的辅助信息，
     * 客户端少报一个字段不该让整次上传失败。
     */
    private Integer normalizeDuration(Integer duration) {
        if (duration == null || duration <= 0 || duration > MAX_VOICE_SECONDS) {
            return null;
        }
        return duration;
    }

    /**
     * 把头像地址同步到用户资料。
     *
     * <p>失败不吞掉：文件已经传上去了但资料没改，用户看到的还是旧头像，
     * 这种情况必须让他知道要重试，静默成功只会换来一句「我明明换了怎么没变」。
     * 代价是可能残留一条没人引用的文件记录，无害。
     */
    private void applyAvatar(Long userId, String avatarUrl) {
        UserProfileSpi profileSpi = userProfileSpiProvider.getIfAvailable();
        if (profileSpi == null) {
            log.warn("[文件] 用户模块未装配，头像已上传但资料未同步: userId={}", userId);
            return;
        }
        profileSpi.updateAvatar(userId, TextUtil.sanitize(avatarUrl, 512));
        log.info("[文件] 头像已同步到用户资料: userId={}", userId);
    }

    /* ==================== 查询与鉴权 ==================== */

    @Override
    public FileEntity requireById(Long fileId) {
        BusinessException.throwIf(fileId == null, ResultCode.BAD_REQUEST, "文件 ID 不能为空");
        FileEntity file = fileMapper.selectById(fileId);
        BusinessException.throwIf(file == null, ResultCode.FILE_NOT_FOUND);
        return file;
    }

    @Override
    public FileEntity requireAccessible(Long viewerId, Long fileId) {
        FileEntity file = requireById(fileId);
        if (!canAccess(file, viewerId)) {
            log.warn("[文件] 拒绝访问: fileId={}, viewerId={}, uploaderId={}, bizType={}",
                    fileId, viewerId, file.getUploaderId(), file.getBizType());
            throw new BusinessException(ResultCode.FILE_DOWNLOAD_FORBIDDEN);
        }
        return file;
    }

    @Override
    public boolean canAccess(FileEntity file, Long userId) {
        if (file == null || userId == null) {
            return false;
        }
        if (userId.equals(file.getUploaderId())) {
            return true;
        }
        // 头像是公开资料：会话列表、群成员列表、资料卡都要渲染别人的头像，
        // 逐个校验会话关系既做不到也没意义
        if (FileBizType.AVATAR.getCode().equals(file.getBizType())) {
            return true;
        }
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        if (messageSpi == null) {
            // 消息模块没装配时一律拒绝：宁可让聊天图片显示成裂图，也不能让文件变成公开可读
            log.warn("[文件] 消息模块未装配，无法判定可见性，按无权处理: fileId={}, userId={}", file.getId(), userId);
            return false;
        }
        return messageSpi.isFileVisibleTo(file.getId(), file.getUploaderId(), userId);
    }

    @Override
    public FileVO toView(FileEntity file, Long viewerId) {
        String signedUrl = viewerId == null ? null : temporaryUrl(file, viewerId, 0);
        return FileConvert.toVO(file, signedUrl);
    }

    @Override
    public String accessUrl(Long fileId) {
        return imProperties.getFile().getAccessUrlPrefix() + "/" + fileId;
    }

    @Override
    public String signedUrl(Long viewerId, Long fileId, long ttlSeconds) {
        FileEntity file = requireAccessible(viewerId, fileId);
        return temporaryUrl(file, viewerId, ttlSeconds);
    }

    /**
     * 生成客户端可以直接使用的临时地址。
     *
     * <p>MinIO 且显式开启 {@code im.file.use-presigned-url} 时返回预签名直链，
     * 流量绕过应用服务器；其余情况返回「受控地址 + 短时票据」，鉴权仍然握在本服务手里。
     * 默认关闭预签名：直链一旦发出去就无法撤销，而票据地址随时可以因为登录态失效而拒绝。
     *
     * @param ttlSeconds 非正数表示使用配置默认值
     */
    private String temporaryUrl(FileEntity file, Long viewerId, long ttlSeconds) {
        long ttl = ttlSeconds > 0 ? ttlSeconds : imProperties.getJwt().getFileTicketTtlSeconds();
        if (fileStorage.type() == StorageType.MINIO && imProperties.getFile().isUsePresignedUrl()) {
            String presigned = fileStorage.presignedUrl(file.getObjectKey(), ttl);
            if (TextUtil.isNotBlank(presigned)) {
                return presigned;
            }
        }
        return file.getUrl() + "?ticket=" + ticketService.issue(file.getId(), viewerId, ttl);
    }

    @Override
    public InputStream openStream(FileEntity file) {
        return storageOf(file).download(file.getObjectKey());
    }

    /**
     * 校验记录所属的存储实现与当前装配的实现是否一致。
     *
     * <p>不一致时给出「存储服务不可用」而不是「文件不存在」：文件确实存在，只是不在这台机器上，
     * 前者会让人去查存储配置，后者会让人去翻数据库，排查方向完全相反。
     */
    private FileStorage storageOf(FileEntity file) {
        String current = fileStorage.type().getCode();
        if (!current.equals(file.getStorageType())) {
            log.warn("[文件] 存储实现不匹配: fileId={}, 记录={}, 当前={}", file.getId(), file.getStorageType(), current);
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
        return fileStorage;
    }
}
