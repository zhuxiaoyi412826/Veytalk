package com.im.remote.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 被控端设备：一台安装了 Agent 的电脑一行，按 (userId, deviceId) 唯一。
 *
 * <p>deviceId 由 Agent 首次启动时生成并持久化在本地配置文件，
 * 重装系统前该 ID 不变，因此同一台机器重新上线只会更新这一行而不会越积越多。
 *
 * <p>status 是「设备档位」而不是连接真相：Agent 掉线由服务端心跳检测置回离线，
 * 会话建立/结束时在 busy 与 idle 之间切换，「拒绝接入」则完全由 Agent 侧用户设置。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("im_remote_device")
public class RemoteDevice extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 离线：Agent 未连接 */
    public static final int STATUS_OFFLINE = 0;
    /** 空闲：在线且可接受远程邀请 */
    public static final int STATUS_IDLE = 1;
    /** 忙：正在被远程会话占用 */
    public static final int STATUS_BUSY = 2;
    /** 拒绝接入：Agent 侧用户开启了免打扰开关 */
    public static final int STATUS_REFUSE = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 设备归属用户 ID */
    private Long userId;

    /** Agent 安装时生成的设备唯一标识 */
    private String deviceId;

    /** 设备名（计算机名），列表展示用 */
    private String deviceName;

    /** 操作系统描述（os.name + os.arch） */
    private String os;

    /**
     * 识别码（ToDesk 式接入码）：Agent 本地设置并随 auth 帧上报，控制方登录自己账号后
     * 凭码发起跨账号远程；码为空表示该设备只走「我的设备」同账号路径。
     */
    private String accessCode;

    /** 状态：0 离线 / 1 空闲 / 2 忙 / 3 拒绝接入 */
    private Integer status;

    /** 最近一次心跳在线时间 */
    private LocalDateTime lastOnlineTime;
}
