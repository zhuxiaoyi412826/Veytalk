package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理后台用的短视频档案，由 im-live 经 {@code AdminVideoSpi} 提供。
 *
 * <p>与用户端的 {@code LiveVideoVO} 分开的理由和 {@link AdminUserDTO} 一样：
 * 用户端列表只含 status=1 的正常视频且不带上传者内部 ID 的检索语义，
 * 后台需要「已下架 / 已逻辑删除前的一条记录」全字段可见，两者口径不同不能共用一个 VO。
 *
 * <p>{@link #fileUrl} / {@link #coverUrl} 是服务端拼好的受控访问地址，
 * 后台预览直接引用；实现方不要在这里塞短时效的预签名地址——
 * 工单式翻页停留几分钟就 404 的预览链接不如没有。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminVideoDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 视频记录 ID（im_live_video 主键） */
    private Long id;

    /** 上传者用户 ID */
    private Long uploaderId;

    /** 上传者昵称（查不到时实现方给「已注销用户」占位，不留 null） */
    private String uploaderName;

    /** 标题 */
    private String title;

    /** 视频受控访问地址 */
    private String fileUrl;

    /** 封面受控访问地址，可为空 */
    private String coverUrl;

    /** 时长（秒） */
    private Integer duration;

    /** 宽（像素） */
    private Integer width;

    /** 高（像素） */
    private Integer height;

    /** 字节数 */
    private Long size;

    /** 扩展名，如 mp4 */
    private String ext;

    /** 播放次数 */
    private Long playCount;

    /** 状态：1 正常 2 已下架（与 im_live_video.status 同口径） */
    private Integer status;

    /** 发布时间 */
    private LocalDateTime createTime;
}
