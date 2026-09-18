package com.im.file.service;

import com.im.common.domain.UploadCmd;
import com.im.file.dto.po.MergedUpload;
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

    /**
     * 整文件秒传：仅凭 MD5 判定服务端是否已存有相同内容的对象，命中就为当前上传者
     * 新建一条指向同一对象键的记录并返回，全程不传输、不写入任何字节。
     *
     * <p>这是分片上传 init 阶段的前置判定：大文件在真正开始切片上传之前先问一句
     * 「这个 MD5 你那边有了吗」，有就直接拿记录走人。与 {@link #store} 里的写时去重不同，
     * 那条路径仍要先把字节收上来才算得出 MD5，省的是存储、不是网络。
     *
     * <p>安全权衡：这里采信客户端上报的 MD5。能报出某个文件 MD5 的前提是本地真的持有该文件，
     * 因此「凭 MD5 领取一份自己并不拥有的文件」在实践中无法构造；同时 {@code size} 也要与已有
     * 记录一致才判定命中，进一步收窄 MD5 碰撞的空间。
     *
     * @return 命中秒传时返回新建的记录；服务端没有可复用对象时返回 {@code null}
     */
    FileEntity instantReuse(Long uploaderId, String bizType, String originalName,
                            String md5, long size, Integer duration);

    /**
     * 分片合并后的流式落库：内容以流的形式提供（{@link MergedUpload#getStreamSupplier()}），
     * {@code md5} 与 {@code size} 已由分片服务重读分片算出并核对。
     *
     * <p>与 {@link #store} 走完全相同的类型 / 大小校验与秒传判定，唯一区别是字节来自流而不是
     * {@code byte[]}，因此可以承载远超内存的大文件。命中秒传时同样只建记录、不写对象。
     */
    FileEntity storeMerged(MergedUpload upload);

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
