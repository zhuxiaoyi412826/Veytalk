package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 直播页「视频」板块的短视频，对应 {@code im_live_video}。
 *
 * <p>与 {@link LiveRoom} 一样是「控制面」数据：视频与封面本体存在 {@code im_file}
 * （bizType = video / video_cover，对所有登录用户公开），本表只存标题、上传者、时长、
 * 尺寸与受控访问地址，供大厅信息流分页展示与播放。<b>一个视频文件一行</b>，与直播的
 * 「一行一场」不同，这里没有状态机与心跳，只有 {@link #STATUS_NORMAL}/{@link #STATUS_OFF}。
 *
 * <p>{@link #fileUrl}/{@link #coverUrl} 存的是长期受控地址 {@code /api/file/download/{id}}，
 * 前端经 {@code mediaUrl} 用带登录头的请求取成 blob 再交给 {@code <video>}/{@code <img>}；
 * 绝不落票据直链（会过期）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_video")
public class LiveVideo extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常（在信息流展示） */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已下架（不展示，预留运营位） */
    public static final int STATUS_OFF = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上传者用户 ID */
    private Long uploaderId;

    private String title;

    /** 视频文件 ID（im_file.id，bizType=video） */
    private Long fileId;

    /** 视频受控访问地址 */
    private String fileUrl;

    /** 封面图文件 ID，可空 */
    private Long coverFileId;

    /** 封面图受控访问地址，可空（前端退化为占位图） */
    private String coverUrl;

    /** 时长（秒），上传前由客户端读取元数据后上报，缺省 0 */
    private Integer duration;

    private Integer width;

    private Integer height;

    /** 文件字节数 */
    private Long size;

    private String ext;

    private String contentType;

    /** 播放次数（每次点开 +1，非去重） */
    private Integer playCount;

    private Integer status;

    /** 是否本人上传（决定前端能否删除） */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(uploaderId);
    }
}
