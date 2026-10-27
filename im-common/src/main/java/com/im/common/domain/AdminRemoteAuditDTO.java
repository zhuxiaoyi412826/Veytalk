package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台用的一条远程会话审计流水，由 im-remote 经 {@code AdminRemoteSpi} 提供。
 *
 * <p>{@code detail} 原文照给：它是这条链路上唯一的「他到底做了什么」——文件路径、
 * 命令行、被拦截的原因都在里面。这一屏因此单独设权限点 {@code system:remote}
 * 而不并进看板（与 {@code system:log} 独立设点同一个理由：能看运营数字的人不该
 * 默认能读到别人机器上的路径与命令）。
 *
 * <p>{@code dangerous} 由服务端判定而不是让前端抄一份动作词表：词表的写侧在
 * 被控端 Agent（{@code FileOps} / {@code SystemOps} 上报的 action 串），
 * 前端再复制一份就出现「改了 Agent 的动词但后台标红还是旧的」这种漂移。
 *
 * <p>后四个上下文字段（控制方 / 被控方 / 设备名 / 权限）是全局检索时才有的东西：
 * 孤零零一条「exec：shutdown」没有归属就无法处置，批量补齐一次会话表 + 两张名字表，
 * 与本页条数无关。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminRemoteAuditDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流水 ID（im_remote_audit_log 主键） */
    private Long logId;

    /** 所属会话 ID */
    private Long sessionId;

    /** 动作标识：invite / accept / reject / control-bound / session-end / direct-up / exec / power / file-rm / file-get / file-put / ps-kill / input-blocked ... */
    private String action;

    /** 动作详情（路径、命令、拦截原因等），写入时已截断到 1000 字符 */
    private String detail;

    /** 触发方：inviter 控制端 / invitee 被控端 Agent / system 服务端流程事件；加列前的历史记录为 null */
    private String actor;

    /** 是否高危动作（见 {@code RemoteAdminSpiImpl} 的词表），前端据此标红，不自己判 */
    private Boolean dangerous;

    /** 记录时间 */
    private LocalDateTime createTime;

    /** 上下文：控制方昵称 */
    private String inviterName;

    /** 上下文：被控方昵称（识别码接入为 null，前端显示「匿名设备」） */
    private String inviteeName;

    /** 上下文：被控设备名（查不到时回落为 deviceId） */
    private String deviceName;

    /** 上下文：会话权限档位 readonly / operate——同一条 exec 在只读会话里根本发不出去 */
    private String permission;
}
