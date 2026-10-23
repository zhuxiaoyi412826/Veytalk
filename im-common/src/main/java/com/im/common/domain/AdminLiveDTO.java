package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理后台用的直播场次档案，由 im-live 经 {@code AdminLiveSpi} 提供。
 *
 * <p>与用户端 {@code LiveRoomVO} 的口径差异：后台列表要能看到<b>全部历史场次</b>
 * （用户端大厅刻意隐藏「结束超 1 小时」的房），且已封禁的场也要完整可查——
 * 处置留痕靠的就是这些行，所以这里连 {@code endReason} 都带上。
 *
 * <p><b>绝不含 streamKey</b>：它是场次的能力凭证，与举报证据
 * （{@code ReportRoomTargetSpiImpl}）同一条红线。播放地址后台也不需要——
 * 管理员要看内容走回放入口，那条路径自带授权判定。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminLiveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 房间 ID（im_live_room 主键） */
    private Long roomId;

    /** 主播用户 ID */
    private Long anchorId;

    /** 主播昵称（查不到时实现方给「已注销用户」占位） */
    private String anchorName;

    /** 直播标题 */
    private String title;

    /** 封面地址，可为空 */
    private String cover;

    /** 公告（主播自述文本，处置判断的主要文字证据） */
    private String notice;

    /** 来源类型：screen 屏幕分享 / camera 摄像头 / chat 聊天室 */
    private String sourceType;

    /** 分辨率，如 1920x1080 */
    private String resolution;

    /** 状态：1 直播中 2 已结束 3 已封禁（与 im_live_room.status 同口径） */
    private Integer status;

    /** 峰值在线 */
    private Integer peakOnline;

    /** 当前在线（仅直播中有实时值，其余场次为关播时定格的 peakOnline 口径） */
    private Integer onlineNow;

    /** 累计观看 */
    private Integer viewerTotal;

    /** 开播时间 */
    private LocalDateTime startTime;

    /** 关播时间，直播中为空 */
    private LocalDateTime endTime;

    /** 结束原因码：stop / timeout / ban，直播中为空 */
    private String endReason;
}
