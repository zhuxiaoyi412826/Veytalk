package com.im.file.spi;

import com.im.common.api.ResultCode;
import com.im.common.domain.FileDTO;
import com.im.common.domain.UploadCmd;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FileStorageSpi;
import com.im.common.util.SecurityUtil;
import com.im.file.convert.FileConvert;
import com.im.file.entity.FileEntity;
import com.im.file.mapper.FileMapper;
import com.im.file.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * {@link FileStorageSpi} 在本模块的实现，目前唯一的调用方是 im-message 的附件消息处理器。
 *
 * <p>这一层的语义与 REST 接口刻意不同：接口查不到就抛 7xxx 异常，SPI 查不到就返回
 * {@code null} / {@code false}。消息模块在发送校验里需要自己决定回什么错误码，
 * 不希望一个来自文件模块的异常穿过去把它的响应体改掉。
 *
 * <p>写入路径（{@link #upload}）则完全复用 {@link FileService#store}：类型白名单、大小上限、
 * MD5 秒传、扩展名到 MIME 的收敛全部只有一份实现，跨模块调用不可能绕过它们。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileStorageSpiImpl implements FileStorageSpi {

    private final FileService fileService;
    private final FileMapper fileMapper;

    @Override
    public FileDTO upload(UploadCmd cmd) {
        return FileConvert.toDTO(fileService.store(cmd));
    }

    @Override
    public FileDTO getById(Long fileId) {
        if (fileId == null) {
            return null;
        }
        FileEntity file = fileMapper.selectById(fileId);
        return file == null ? null : FileConvert.toDTO(file);
    }

    @Override
    public InputStream openStream(FileDTO file) {
        BusinessException.throwIf(file == null || file.getFileId() == null, ResultCode.FILE_NOT_FOUND);
        // 按 ID 回表而不是直接用 DTO 里的 objectKey：DTO 是调用方手里可能已经过期的快照，
        // 而「这条记录属于哪种存储实现」的一致性校验必须以库里的当前值为准
        return fileService.openStream(fileService.requireById(file.getFileId()));
    }

    @Override
    public String accessUrl(Long fileId) {
        return fileService.accessUrl(fileId);
    }

    @Override
    public String presignedUrl(Long fileId, long ttlSeconds) {
        Long viewerId = SecurityUtil.getUserIdOrNull();
        if (viewerId != null) {
            return fileService.signedUrl(viewerId, fileId, ttlSeconds);
        }
        // 没有请求上下文（系统内部调用）时按上传者本人签发：调用方能用这个 SPI，
        // 说明它已经在自己的业务里确认过权限；这里硬猜一个「代表谁」只会猜错
        FileEntity file = fileService.requireById(fileId);
        return fileService.signedUrl(file.getUploaderId(), fileId, ttlSeconds);
    }

    @Override
    public boolean canAccess(Long fileId, Long userId) {
        if (fileId == null) {
            return false;
        }
        // 文件不存在时交给 canAccess 收到 null 后返回 false，不在这里抛 FILE_NOT_FOUND：
        // 「查不到」和「不给看」对调用方是同一个答案
        return fileService.canAccess(fileMapper.selectById(fileId), userId);
    }
}
