package com.im.file.service;

import com.im.common.domain.UploadCmd;
import com.im.file.dto.vo.FileVO;
import com.im.file.entity.FileEntity;

import java.io.InputStream;

/**
 * 文件服务。
 *
 * <p>上传、鉴权、地址签发三件事全部收在这里，控制器只负责在 {@code MultipartFile}
 * 与 {@link UploadCmd} 之间做搬运：跨模块 SPI（{@code FileStorageSpiImpl}）与 REST 接口
 * 必须走完全相同的校验路径，否则「接口拦得住、SPI 拦不住」迟早会变成越权漏洞。
 *
 * <p>文件内容以 {@code byte[]} 形式在内存里过一遍（{@link UploadCmd#getBytes()}），
 * 上限由 {@code im.file.max-size} 兜住（默认 20MB）。真正的流式方案需要存储层支持
 * 边读边算 MD5 与边读边写，而秒传又要求先有完整 MD5 才能决定要不要写，
 * 在这个量级上换复杂度不值得。
 */
public interface FileService {

    /* ==================== 上传 ==================== */

    /**
     * 上传核心入口：类型与大小校验 -&gt; MD5 秒传判定 -&gt; 写字节 -&gt; 落元数据 -&gt; 头像回写。
     *
     * @return 落库后的元数据记录；命中秒传时是一条指向已有对象键的新记录
     */
    FileEntity store(UploadCmd cmd);

    /* ==================== 查询与鉴权 ==================== */

    /**
     * 按 ID 查询元数据，不存在时抛 {@code FILE_NOT_FOUND}。
     */
    FileEntity requireById(Long fileId);

    /**
     * 校验访问权后返回元数据，下载与临时地址签发的前置步骤。
     *
     * @throws com.im.common.exception.BusinessException 无权访问时抛 {@code FILE_DOWNLOAD_FORBIDDEN}
     */
    FileEntity requireAccessible(Long viewerId, Long fileId);

    /**
     * 用户能否访问该文件：本人上传、公开头像，或与该文件所在的消息同处一个会话。
     */
    boolean canAccess(FileEntity file, Long userId);

    /**
     * 转成前端视图，同时为 {@code viewerId} 签发一个可直接渲染的临时地址。
     */
    FileVO toView(FileEntity file, Long viewerId);

    /**
     * 长期有效的受控访问地址（不含票据），可以安全落库。
     */
    String accessUrl(Long fileId);

    /**
     * 可直接使用的临时地址：MinIO 且开启预签名时返回直链，否则返回「受控地址 + 短时票据」。
     */
    String signedUrl(Long viewerId, Long fileId, long ttlSeconds);

    /**
     * 打开文件内容流，调用方负责关闭。
     *
     * <p>会先校验元数据里的存储实现与当前装配的实现是否一致，避免切换存储后
     * 拿着一条本地磁盘的记录去 MinIO 里找一个不存在的对象。
     */
    InputStream openStream(FileEntity file);
}
