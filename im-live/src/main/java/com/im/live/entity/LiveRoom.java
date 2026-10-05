package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 直播房间，对应 {@code im_live_room}。
 *
 * <p>一行 = 一场直播（而不是一个「频道」）：主播每次开播都新建一行，关播置
 * {@link #STATUS_ENDED} 并写 {@code endTime}，这样每场的时长、累计观看、录制文件
 * 都有独立的落点，回放列表直接查本表即可。
 *
 * <p>{@link #streamKey} 是这场直播的<b>能力凭证</b>：它同时是推流目录名与播放目录名
 * （{@code /{roomId}/{streamKey}/index.m3u8}），随机生成、不可猜、每场都换。
 * 有了它，即便播放地址的签名过期机制被绕过，攻击者也需要先拿到本场的 key 才能定位分片目录。
 * 因此该字段绝不出现在任何列表接口的响应里，只在开播响应中给主播本人一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_live_room")
public class LiveRoom extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：直播中 */
    public static final int STATUS_LIVING = 1;
    /** 状态：已结束（正常关播或心跳超时自动关播） */
    public static final int STATUS_ENDED = 2;
    /** 状态：已封禁（管理端强制断播，主播不能再用心跳复活） */
    public static final int STATUS_BANNED = 3;

    /** 推流源：屏幕分享 */
    public static final String SOURCE_SCREEN = "screen";
    /** 推流源：摄像头 */
    public static final String SOURCE_CAMERA = "camera";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主播用户 ID */
    private Long anchorId;

    private String title;

    /** 封面图访问地址，可空（前端退化成占位图） */
    private String cover;

    /** 房间公告，进房时随弹幕通道下发一次 */
    private String notice;

    /** 推流源类型：screen / camera，只用于前端展示图标，不影响服务端行为 */
    private String sourceType;

    /** 分辨率档位标签，如 720p / 1080p，由推流端声明，服务端只存不校验 */
    private String resolution;

    /** 视频码率（kbps），由推流端声明，用于列表展示与带宽估算 */
    private Integer bitrateKbps;

    /** 本场推流密钥，见类注释；关播后置空以彻底作废本场地址 */
    private String streamKey;

    private Integer status;

    /** 峰值在线人数：心跳/进出房时取最大值写入，关播时定格 */
    private Integer peakOnline;

    /** 累计观看人次（连接建立次数，同一人反复进出会重复计），关播时定格 */
    private Integer viewerTotal;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 关播原因：stop 主播主动 / timeout 心跳超时 / ban 管理封禁，为空表示仍在直播 */
    private String endReason;

    public boolean isLiving() {
        return status != null && status == STATUS_LIVING;
    }

    public boolean isEnded() {
        return status != null && status == STATUS_ENDED;
    }

    public boolean isBanned() {
        return status != null && status == STATUS_BANNED;
    }

    /**
     * 是否由本人管理（关播、改标题）。
     *
     * <p>放在实体上而不是散在各处比较 {@code anchorId.equals(userId)}：
     * 后者在 {@code anchorId} 为 null 时会抛 NPE，而这种 null 只可能来自脏数据，
     * 不该让它变成接口 500。
     */
    public boolean managedBy(Long userId) {
        return userId != null && userId.equals(anchorId);
    }
}
