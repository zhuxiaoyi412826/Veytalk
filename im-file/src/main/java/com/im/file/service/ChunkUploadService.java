package com.im.file.service;

import com.im.file.dto.req.UploadInitReq;
import com.im.file.dto.vo.UploadChunkVO;
import com.im.file.dto.vo.UploadInitVO;
import com.im.file.entity.FileEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 分片上传（断点续传 + 秒传）服务。
 *
 * <p>把「大文件切成小片、逐片上传、最后合并」这件事与 {@link FileService} 分开：后者只关心
 * 「一份完整内容如何落库」，前者负责临时分片的会话管理、断点续传与合并前的完整性校验。
 * 合并出可信的 {@code md5} 与 {@code size} 之后，仍然交回 {@link FileService#storeMerged} 走
 * 与普通上传完全相同的校验与秒传路径，避免出现「分片上传能绕过类型白名单」这种口子。
 *
 * <p>会话状态全部落在临时目录（一个 uploadId 一个子目录，内含 meta 与若干 {@code .part}），
 * 不引入 Redis：断点续传要的就是「进程重启后分片还在」，磁盘天然满足，而缓存反而会在重启后丢失。
 */
public interface ChunkUploadService {

    /**
     * 初始化一次分片上传。
     *
     * <p>先按整文件 MD5 判定能否秒传，命中直接返回文件记录；否则建立（或复用已存在的）上传会话，
     * 下发权威分片大小与「服务端已收到的分片下标」，前端据此只补传缺失的分片。
     */
    UploadInitVO init(Long userId, UploadInitReq req);

    /**
     * 上传单个分片。分片以「先写临时文件再原子改名」的方式落盘，重复投递同一分片是幂等的。
     *
     * @return 落盘后的分片下标与服务端当前已收到的全部分片下标
     */
    UploadChunkVO storeChunk(Long userId, String uploadId, int chunkIndex, MultipartFile part);

    /**
     * 合并全部分片并落库。
     *
     * <p>合并前会重读所有分片、重算 MD5 与字节数并与 init 时声明的值逐一核对，
     * 对不上直接拒绝——这是防止客户端谎报 MD5 拼出非法内容的最后一道关。成功后清理临时目录。
     */
    FileEntity merge(Long userId, String uploadId);
}
