package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台用的远程会话档案，由 im-remote 经 {@code AdminRemoteSpi} 提供。
 *
 * <p>与用户端 {@code RemoteSessionVO} 的三点差异：
 * <ol>
 *   <li><b>两端身份都要给</b>。用户端只关心「对端是谁」（我自己那一方界面里已知），
 *       而后台要追责，必须同时看到控制方与被控方，所以这里是 {@code inviterName} +
 *       {@code inviteeName} 两列，而不是一个 {@code peerName}；</li>
 *   <li><b>没有 role 字段</b>。那个字段的意义是「我在这段关系里的位置」，
 *       对没有「自己」的后台视角无意义；</li>
 *   <li><b>多了两个实时态</b>（{@code agentOnline} / {@code relaying}）：
 *       库里的 status 是<b>状态机</b>，而管理员开这一页想知道的是<b>此刻真相</b>——
 *       status=active 但控制端还没凭票接入时并没有任何画面在传，
 *       status=ended 也可能是收尾落库前那一瞬。两个值来自服务端内存的连接表，不落库。</li>
 * </ol>
 *
 * <p><b>绝不含 accessCode</b>：识别码是「任意登录用户可对该设备发起邀请」的接入凭证，
 * 与直播的 streamKey 同一条红线——审计只需要知道是哪台机器（deviceName + deviceId
 * 机器指纹足够），把凭证带在台账里等于给后台多发一份可用钥匙。
 * ticket / aesKey 同理，它们本来就只存在于内存与 Redis，不在库里。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminRemoteSessionDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话 ID（im_remote_session 主键） */
    private Long sessionId;

    /** 被控设备标识（机器指纹，设备行被清理后它是唯一线索，不能被占位符替换） */
    private String deviceId;

    /** 设备名（计算机名）；查不到时实现方回落为 deviceId */
    private String deviceName;

    /** 操作系统描述（Agent auth 帧上报，如 Windows 11 amd64） */
    private String deviceOs;

    /** 控制方用户 ID */
    private Long inviterUserId;

    /** 控制方昵称（账号已注销时为「已注销用户」占位） */
    private String inviterName;

    /** 被控方用户 ID；识别码模式下为 0（设备不归属任何账号） */
    private Long inviteeUserId;

    /** 被控方昵称；为 null 时前端显示「匿名设备」（inviteeUserId=0 的识别码接入） */
    private String inviteeName;

    /** 权限：readonly 只读 / operate 可操作（被控端授权时可能已降档） */
    private String permission;

    /** 状态机：inviting / active / rejected / ended */
    private String status;

    /** 邀请创建时间 */
    private LocalDateTime createTime;

    /** 中继建立时间；从未被授权过为 null */
    private LocalDateTime startTime;

    /** 会话结束时间；进行中为 null */
    private LocalDateTime endTime;

    /** 结束原因：inviter-end / invitee-end / agent-offline / control-offline / idle-timeout / invite-timeout / superseded / rejected */
    private String endReason;

    /** 会话时长（秒）：已结束按 start→end，进行中按 start→now，未建立过连接为 0 */
    private Long durationSeconds;

    /** 总流量（字节）：进行中的会话取内存中继累计值，已结束的取收尾落库值 */
    private Long bytes;

    /** 平均码率（Mbps，按 10^6 与云厂商规格表同口径）；时长为 0 时为 null */
    private Double bitrateMbps;

    /** 该会话的审计流水条数，用来提示「点进去有没有东西」 */
    private Long auditCount;

    /** 此刻该 (归属人, deviceId) 是否有已认证的 Agent 连接（服务端内存真相，非库字段） */
    private Boolean agentOnline;

    /** 此刻是否有控制端已凭票接入的中继绑定（true 才真的在传画面） */
    private Boolean relaying;

    /** 当前媒体路径：tcp 局域网直连 / udp 打洞直连 / null 表示仍走中继或未在进行中 */
    private String directPath;
}
