package com.im.remote.dto.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 远程会话历史的展示视图。
 *
 * <p>实体 {@code RemoteSession} 只有 ID 与外键，直接返给前端的话列表里全是
 * 「一串 deviceId + 一个用户 ID + 英文 endReason」，认不出是哪台机器、也分不清
 * 「我控了别人」还是「别人控了我」。这里把三样关联信息在分页内一次性补齐：
 * 设备名（批量查 im_remote_device）、对端昵称（批量走 UserQuerySpi）、审计条数（一次 GROUP BY），
 * 并顺手算出时长与平均码率——总流量单看没有意义，配上时长才知道是「短时高码率」还是「挂了一整晚」。
 *
 * <p>刻意不含 ticket / aesKey：那是会话建立窗口内的一次性凭证，历史列表没有任何理由携带。
 */
@Data
@Builder
public class RemoteSessionVO {

    /** 我在本会话里的角色：控制方 */
    public static final String ROLE_INVITER = "inviter";
    /** 我在本会话里的角色：被控方 */
    public static final String ROLE_INVITEE = "invitee";

    private Long id;

    /** 被控设备标识（机器指纹，认不出机器时用它兜底显示） */
    private String deviceId;

    /** 被控设备名称；设备行已被清理时回落为 deviceId */
    private String deviceName;

    /** 我的角色：inviter 我控别人 / invitee 别人控我 */
    private String role;

    /** 对端用户 ID；匿名识别码接入时为 0 */
    private Long peerUserId;

    /** 对端昵称；匿名接入或用户已注销时为 null，由前端显示「匿名设备」 */
    private String peerName;

    /** 权限：readonly / operate（被控端授权时可能已降档） */
    private String permission;

    /** 状态：inviting / active / rejected / ended */
    private String status;

    /** 会话开始（中继建立）时间；未被授权过则为 null */
    private LocalDateTime startTime;

    /** 会话结束时间；进行中为 null */
    private LocalDateTime endTime;

    /** 会话时长（秒）：已结束按 start→end，进行中按 start→now，未建立过连接为 0 */
    private Long durationSeconds;

    /** 中继 + 直连合计流量（字节） */
    private Long bytes;

    /** 平均码率（Mbps，按 10^6 与云厂商规格表同口径）；时长为 0 时为 null */
    private Double bitrateMbps;

    /** 结束原因：inviter-end / invitee-end / agent-offline / control-offline / idle-timeout / invite-timeout / superseded / rejected */
    private String endReason;

    /** 该会话的审计流水条数，列表上用来提示「点进去有没有东西」 */
    private Long auditCount;

    /** 邀请创建时间 */
    private LocalDateTime createTime;
}
