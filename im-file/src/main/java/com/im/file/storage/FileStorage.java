package com.im.file.storage;

import com.im.common.enums.StorageType;

import java.io.InputStream;

/**
 * 存储抽象，屏蔽本地磁盘与 MinIO 的差异。
 *
 * <p>对象键由上层生成并传入，实现只负责「把这个键对应的字节存下来 / 取出来」：
 * 键的生成规则一旦下沉到实现里，两种实现就会各自演化出不同的目录结构，
 * 切换存储时历史记录全部失效。
 *
 * <p>实现类通过 {@code @ConditionalOnProperty(im.file.storage)} 二选一装配，
 * 容器里任何时刻只存在一个 {@link FileStorage} Bean，上层直接注入接口即可。
 */
public interface FileStorage {

    /**
     * 写入字节。
     *
     * @param in          文件内容，调用方负责关闭
     * @param size        字节数；未知时传 -1，由实现自行决定是否需要边读边算
     * @param contentType MIME 类型，可为空
     * @param objectKey   对象键
     * @return 实际写入的字节数
     */
    long upload(InputStream in, long size, String contentType, String objectKey);

    /**
     * 打开读取流，调用方负责关闭。
     *
     * @throws com.im.common.exception.BusinessException 对象不存在时抛 {@code FILE_NOT_FOUND}
     */
    InputStream download(String objectKey);

    /**
     * 判断对象当前是否仍存在于存储中。
     *
     * <p>专供秒传复用前的存在性校验：源对象可能被 MinIO 控制台手工删除或被生命周期策略回收，
     * 而 {@code im_file} 里的 md5 记录还在。复用前确认对象真的在，才不会写出一条指向空气的新记录。
     *
     * @return 对象确认存在返回 {@code true}；已不存在、或存储暂时不可用无法确认时返回 {@code false}
     *         （宁可放弃一次秒传转为重新上传，也不复用可能失效的对象键）
     */
    boolean exists(String objectKey);

    /**
     * 生成临时直链。
     *
     * @param ttlSeconds 期望有效期（秒），实现可按自身上限收敛
     * @return 不支持预签名的实现返回 {@code null}，由上层改用后端受控地址 + 短时票据
     */
    String presignedUrl(String objectKey, long ttlSeconds);

    /**
     * 删除对象，对象不存在时静默返回。
     */
    void delete(String objectKey);

    /**
     * 存储桶名，本地实现返回 {@code null}，落库时用于标记这份字节到底在哪。
     */
    String bucket();

    /**
     * 存储类型，写入 {@code im_file.storage_type}。
     */
    StorageType type();
}
