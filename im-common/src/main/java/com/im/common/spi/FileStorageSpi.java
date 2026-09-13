package com.im.common.spi;

import com.im.common.domain.FileDTO;
import com.im.common.domain.UploadCmd;

import java.io.InputStream;

/**
 * 文件存储契约，由 im-file 模块实现，底层可切换 MinIO 与本地磁盘。
 */
public interface FileStorageSpi {

    /**
     * 上传文件并保存元数据。相同 MD5 命中秒传时直接返回已存在的记录。
     */
    FileDTO upload(UploadCmd cmd);

    /**
     * 查询文件元数据。
     *
     * @return 不存在时返回 {@code null}
     */
    FileDTO getById(Long fileId);

    /**
     * 打开文件输入流，调用方负责关闭。
     */
    InputStream openStream(FileDTO file);

    /**
     * 返回后端受控访问地址（需登录态），前端可直接用于 img src 之外的场景。
     */
    String accessUrl(Long fileId);

    /**
     * 返回临时直链地址；MinIO 实现返回预签名 URL，本地实现返回带一次性 token 的后端地址。
     *
     * @param ttlSeconds 有效期（秒）
     */
    String presignedUrl(Long fileId, long ttlSeconds);

    /**
     * 判断用户是否有权访问该文件：本人上传、或与该文件所在会话/群有关联。
     */
    boolean canAccess(Long fileId, Long userId);
}
