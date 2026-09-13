package com.im.file.config;

import com.im.common.config.ImProperties;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配，仅在 {@code im.file.storage=minio} 时生效。
 *
 * <p>与 {@link com.im.file.storage.MinioFileStorage} 用同一个条件：默认的本地存储模式下
 * 连 {@code MinioClient} 这个 Bean 都不该存在，否则一个没启动的 MinIO 会让整个应用在
 * 与文件功能毫不相干的场景下报错。
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "im.file.storage", havingValue = "minio")
public class MinioClientConfig {

    @Bean
    public MinioClient minioClient(ImProperties imProperties) {
        ImProperties.File.Minio minio = imProperties.getFile().getMinio();
        log.info("[MinIO] 初始化客户端: endpoint={}, bucket={}", minio.getEndpoint(), minio.getBucket());
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }
}
