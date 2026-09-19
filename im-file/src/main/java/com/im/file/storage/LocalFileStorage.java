package com.im.file.storage;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.enums.StorageType;
import com.im.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储，默认实现（{@code im.file.storage} 未配置时也走这里）。
 *
 * <p>不装 MinIO 也能把整套聊天跑通是本模块的硬要求：对象存储是部署依赖，
 * 不该成为本地开发的门槛。切到 MinIO 只需改一行配置，对象键与元数据结构完全一致。
 *
 * <p>所有落盘路径都经过 {@link #resolve} 归一化并校验仍在根目录之内，
 * 因为 {@code objectKey} 来自数据库、而数据库里的值来自上传时拼出的文件名扩展名，
 * 一个 {@code ../../} 就能把文件写到系统任意位置。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "im.file.storage", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(ImProperties imProperties) {
        String configured = imProperties.getFile().getLocal().getBaseDir();
        this.baseDir = Paths.get(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            // 启动即失败而不是等到第一次上传：目录不可写属于部署问题，越晚暴露越难定位
            throw new IllegalStateException("本地文件存储根目录创建失败: " + baseDir, e);
        }
        log.info("[本地存储] 初始化完成，根目录 = {}", baseDir);
    }

    @Override
    public long upload(InputStream in, long size, String contentType, String objectKey) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            long written = Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[本地存储] 写入成功: key={}, bytes={}", objectKey, written);
            return written;
        } catch (IOException e) {
            log.error("[本地存储] 写入失败: key={}, target={}", objectKey, target, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public InputStream download(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.isRegularFile(target)) {
            log.warn("[本地存储] 对象不存在: key={}, target={}", objectKey, target);
            throw new BusinessException(ResultCode.FILE_NOT_FOUND);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            log.error("[本地存储] 打开失败: key={}", objectKey, e);
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            return Files.isRegularFile(resolve(objectKey));
        } catch (BusinessException e) {
            // resolve 对空键 / 越界键会抛异常：这类键本就不该命中复用，视为不存在
            return false;
        }
    }

    @Override
    public String presignedUrl(String objectKey, long ttlSeconds) {
        // 本地磁盘没有「签名直链」这个概念：文件不在任何可公网访问的对象存储里，
        // 返回 null 让上层改走「受控下载地址 + 短时票据」，两者对外表现一致
        return null;
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            // 删除失败不抛异常：调用方关心的是「这条记录不再可用」，残留一个孤儿文件不影响正确性
            log.error("[本地存储] 删除失败: key={}", objectKey, e);
        }
    }

    @Override
    public String bucket() {
        return null;
    }

    @Override
    public StorageType type() {
        return StorageType.LOCAL;
    }

    /**
     * 把对象键解析成根目录下的绝对路径，并拒绝任何越界访问。
     */
    private Path resolve(String objectKey) {
        BusinessException.throwIf(objectKey == null || objectKey.isBlank(), ResultCode.FILE_NOT_FOUND);
        Path target = baseDir.resolve(objectKey).normalize();
        if (!target.startsWith(baseDir)) {
            log.warn("[本地存储] 拒绝越界对象键: key={}", objectKey);
            throw new BusinessException(ResultCode.FILE_DOWNLOAD_FORBIDDEN);
        }
        return target;
    }
}
