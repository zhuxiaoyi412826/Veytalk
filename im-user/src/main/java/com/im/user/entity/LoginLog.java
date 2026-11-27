package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 一条登录或设备变动流水，对应表 {@code im_login_log}（只 INSERT，不更新不软删）。
 *
 * <p>与 {@link UserDevice} 分表的意义：设备表答「现在哪些机器在用」，是一张会被覆盖的状态表；
 * 本表答「谁在什么时候从哪个 IP 进来过、被谁挤掉了」。出盗号投诉时要的是后者，
 * 而状态表里的 {@code last_login_time} 只有一个，历史全被下一次登录盖掉了。
 *
 * <p>{@code updateTime} 是 {@link AuditEntity} 带出来的，本表业务上永不 UPDATE——
 * 宁可多一个恒等于创建时间的列，也不让流水实体脱离全站统一的时间戳口径。
 *
 * <p>保留期由 {@code im.account.login-log-keep-days} 控制，到期物理删除；
 * 设为 0 表示永不清理，此时这张表会随登录量单调增长。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_login_log")
public class LoginLog extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 登录成功（四种登录方式都落这一档，具体方式写在 {@link #detail} 里） */
    public static final int EVENT_LOGIN = 1;
    /** 登录失败（账号不存在 / 密码错 / 被禁用），此时设备信息可能不全 */
    public static final int EVENT_LOGIN_FAIL = 2;
    /** 用户主动注销当前设备 */
    public static final int EVENT_LOGOUT = 3;
    /** 被同端的新登录顶下线 */
    public static final int EVENT_REPLACED = 4;
    /** 被管理后台踢下线或封号 */
    public static final int EVENT_KICKED = 5;
    /** 在设备管理页撤销了这台设备 */
    public static final int EVENT_REVOKED = 6;
    /** 改密码 / 改绑手机 / 改绑邮箱导致的全端下线 */
    public static final int EVENT_CREDENTIAL_CHANGED = 7;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 事件类型，见 {@link #EVENT_LOGIN} 等常量 */
    private Integer event;

    /** 设备类型码；登录失败（连账号都不存在）时可能为空串 */
    private String deviceType;

    /** 安装实例 ID；老客户端为空串 */
    private String deviceId;

    /** 设备名（上送的友好名，UA 兜底），仅展示 */
    private String deviceName;

    /** 客户端 IP（未接归属地库，只到 IP） */
    private String ip;

    private String userAgent;

    /** 补充说明：失败原因 / 顶掉它的那台设备名 / 操作来源（如 bind-phone） */
    private String detail;
}
