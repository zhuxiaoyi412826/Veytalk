package com.im.file.storage;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.enums.StorageType;
import com.im.common.exception.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * MinIO 对象存储实现，{@code im.file.storage=minio} 时装配。
 *
 * <p>启动时自动建桶：桶不存在属于「第一次部署」而不是「配置错误」，
 * 让人先去控制台点一下再启动服务，只会让 README 多一条容易漏看的步骤。
 *
 * <p>MinIO 客户端的方法一律抛出七八个受检异常（网络、XML 解析、加密、服务端错误各一套），
 * 逐个 catch 再分别包装成不同错误码没有任何收益——对调用方而言只有「传得上 / 传不上」两种结果，
 * 因此这里统一 catch {@code Exception} 并保留原始堆栈到日志。唯一的例外是下载时的
 * {@code NoSuchKey}，它必须翻译成「文件不存在」而不是「存储服务不可用」，
 * 否则用户会对着一个早就被删掉的文件反复重试，而运维会去查一个健康的 MinIO。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "im.file.storage", havingValue = "minio")
public class MinioFileStorage implements FileStorage {

    /** 预签名 URL 的协议上限：7 天，超过会被 MinIO 直接拒绝 */
    private static final int MAX_PRESIGNED_SECONDS = 7 * 24 * 60 * 60;

    /** 对象大小未知时的分片大小，MinIO 要求不小于 5MiB */
    private static final long DEFAULT_PART_SIZE = 10L * 1024 * 1024;

    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final MinioClient minioClient;
    private final String bucket;

    public MinioFileStorage(MinioClient minioClient, ImProperties imProperties) {
        this.minioClient = minioClient;
        this.bucket = imProperties.getFile().getMinio().getBucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                log.info("[MinIO] 存储桶已存在: {}", bucket);
                return;
            }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[MinIO] 存储桶创建成功: {}", bucket);
        } catch (Exception e) {
            // 刻意让启动失败：显式选了 MinIO 却连不上，继续启动只会把问题推迟到第一次上传
            throw new IllegalStateException(
                    "MinIO 存储桶初始化失败: " + bucket + "，请确认服务已启动、endpoint 与凭据正确", e);
        }
    }

    @Override
    public long upload(InputStream in, long size, String contentType, String objectKey) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    // 大小已知时 partSize 传 -1 让 MinIO 一次写完；未知时才需要分片
                    .stream(in, size, size > 0 ? -1 : DEFAULT_PART_SIZE)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
            log.debug("[MinIO] 写入成功: bucket={}, key={}, bytes={}", bucket, objectKey, size);
            return size;
        } catch (Exception e) {
            log.error("[MinIO] 写入失败: bucket={}, key={}", bucket, objectKey, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if (NO_SUCH_KEY.equals(code)) {
                log.warn("[MinIO] 对象不存在: bucket={}, key={}", bucket, objectKey);
                throw new BusinessException(ResultCode.FILE_NOT_FOUND);
            }
            log.error("[MinIO] 读取被服务端拒绝: bucket={}, key={}, code={}", bucket, objectKey, code, e);
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        } catch (Exception e) {
            log.error("[MinIO] 读取失败: bucket={}, key={}", bucket, objectKey, e);
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public String presignedUrl(String objectKey, long ttlSeconds) {
        int expiry = (int) Math.min(Math.max(ttlSeconds, 1L), MAX_PRESIGNED_SECONDS);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expiry)
                    .build());
        } catch (Exception e) {
            log.error("[MinIO] 生成预签名地址失败: bucket={}, key={}", bucket, objectKey, e);
            throw new BusinessException(ResultCode.FILE_STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("[MinIO] 删除失败: bucket={}, key={}", bucket, objectKey, e);
        }
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public StorageType type() {
        return StorageType.MINIO;
    }
}
