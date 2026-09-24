package com.im.remote.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.im.common.entity.AuditEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 远程会话：一次「邀请 → 授权 → 中继 → 结束」的完整生命周期。
 *
 * <p>ticket 不存明文：控制端连接凭证只在 Redis 里以 {@code remote:ticket:{ticket} -> sessionId}
 * 短暂存在（60 秒、一次性消费），库里只留 endReason/bytes 等审计所需的收尾字段。
 *
 * <p>aesKey 同样不入库——密钥只在会话存续期由服务端内存持有并分别下发给两端，
 * 落库意味着审计库成了能解密历史流量的地方，这比方便调试重要得多。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_remote_session")
public class RemoteSession extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 已邀请，等待被控端授权 */
    public static final String STATUS_INVITING = "inviting";
    /** 已授权，中继工作中 */
    public static final String STATUS_ACTIVE = "active";
    /** 被控端拒绝 */
    public static final String STATUS_REJECTED = "rejected";
    /** 已结束（正常结束 / 超时 / 掉线） */
    public static final String STATUS_ENDED = "ended";

    /** 只读：仅画面与文件浏览，服务端拦截一切输入帧 */
    public static final String PERMISSION_READONLY = "readonly";
    /** 可操作：完整键鼠控制 */
    public static final String PERMISSION_OPERATE = "operate";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被控方（设备归属人）用户 ID */
    private Long inviteeUserId;

    /** 被控设备标识 */
    private String deviceId;

    /** 控制方用户 ID */
    private Long inviterUserId;

    /** 权限：readonly / operate，被控端授权时可降档 */
    private String permission;

    /** 状态：inviting / active / rejected / ended */
    private String status;

    /** 会话开始（中继建立）时间 */
    private LocalDateTime startTime;

    /** 会话结束时间 */
    private LocalDateTime endTime;

    /** 结束原因：inviter-end / invitee-end / timeout / rejected / offline / control-offline */
    private String endReason;

    /** 中继转发的总字节数（双向合计），审计与限流参考 */
    private Long bytes;

    /**
     * 被控设备的 Agent 连接标识（内存字段，邀请时写入、accept 后供 control-ready 路由）。
     *
     * <p>不建表列：它本质是会话建立窗口内的路由缓存，会话结束后毫无价值，
     * 落库反而会让重启后出现「指向不存在连接」的死数据。
     */
    @TableField(exist = false)
    @JsonIgnore
    private String device;

    /**
     * 控制端一次性连接票据（内存字段，60 秒有效，消费即焚）。
     */
    @TableField(exist = false)
    @JsonIgnore
    private String ticket;

    /**
     * 会话级 AES-256 密钥：只在邀请→绑定的内存窗口里存在，不落库。
     *
     * <p>落库意味着审计库能解密历史流量，而它没有任何解密场景；
     * 密钥经两端的已鉴权通道各发一份，会话结束即随之销毁。
     */
    @TableField(exist = false)
    @JsonIgnore
    private byte[] aesKey;
}
