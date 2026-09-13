package com.im.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 文件元数据，对应 {@code im_file}。
 *
 * <p>类名刻意不叫 {@code File}：本模块的存储实现必须使用 {@code java.io.File}，
 * 同名的话每个 import 都要靠上下文猜是哪个 File，重构成 bug 只是时间问题。
 *
 * <p>一行记录代表「一次上传」，不代表「一份磁盘上的字节」：MD5 秒传时不同上传者会各自拿到一行，
 * 但共享同一个 {@code objectKey}。这样做是因为消息发送时会校验「文件的上传者必须是发消息的人」，
 * 如果秒传直接返回别人的记录，第二个上传者就再也发不出这个文件了。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_file")
public class FileEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上传者用户 ID，文件归属与访问鉴权的起点 */
    private Long uploaderId;

    /** 业务类型，取值见 {@link com.im.file.enums.FileBizType} */
    private String bizType;

    /** 存储实现，取值见 {@link com.im.common.enums.StorageType}；记录在行上而不是读配置，
     *  这样切换存储实现后旧文件仍然知道该去哪里取 */
    private String storageType;

    /** 存储桶，本地实现为空 */
    private String bucket;

    /** 对象键 / 相对路径，形如 {@code 2025/01/01/{uuid}.png}，两种存储实现共用同一套键 */
    private String objectKey;

    /** 原始文件名，仅用于下载时的 Content-Disposition 与前端展示 */
    private String originalName;

    /** 长期有效的访问地址（后端受控下载地址），不含票据，可安全落库 */
    private String url;

    /** 文件字节数 */
    private Long size;

    /** MIME 类型 */
    private String contentType;

    /** 扩展名，不含点，小写 */
    private String ext;

    /** 文件 MD5，秒传依据 */
    private String md5;

    /** 语音时长（秒），仅语音文件有值 */
    private Integer duration;
}
