package com.im.file.dto.po;

import lombok.Builder;
import lombok.Data;

import java.io.InputStream;
import java.util.function.Supplier;

/**
 * 分片合并后的落库指令，{@code FileService#storeMerged} 的入参。
 *
 * <p>与 {@code UploadCmd} 的关键区别：内容不再是一个 {@code byte[]}，而是一个「按需打开的流工厂」
 * {@link #streamSupplier}。大视频动辄上 GB，一次性读进内存会直接打爆堆；这里让存储层边读边写，
 * 内存占用恒定。工厂模式（而不是直接给一个 {@link InputStream}）是为了支持秒传判定——
 * 若合并前发现相同 MD5 的对象已存在，就不会去打开这个流，避免无谓地读一遍磁盘。
 *
 * <p>{@code md5} 与 {@code size} 由分片服务在合并前重读全部分片算出并与客户端声明值核对过，
 * 到这里已是可信值；{@code FileService} 仍会用它们做秒传判定，但不再重新计算。
 */
@Data
@Builder
public class MergedUpload {

    /** 上传者用户 ID */
    private Long uploaderId;

    /** 业务类型 code，取值见 {@link com.im.file.enums.FileBizType} */
    private String bizType;

    /** 原始文件名 */
    private String originalName;

    /** 整文件字节数，已核对 */
    private long size;

    /** 整文件 MD5，已核对 */
    private String md5;

    /** 语音时长（秒），仅语音文件有值 */
    private Integer duration;

    /** 内容流工厂，每次调用返回一个从头读取的新流，调用方负责关闭 */
    private Supplier<InputStream> streamSupplier;
}
