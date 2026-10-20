package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理后台用的用户档案，由 im-user 经 {@code AdminUserSpi} 提供。
 *
 * <p>与面向用户的 {@code UserBriefDTO} 分开是刻意的：那个是「会话里显示谁」的最小集合，
 * 这个是「管理员判断要不要处置」的判断材料（账号状态、注册时间、积分余额），
 * 合并成一份会让普通接口也一并暴露出余额与状态位。
 *
 * <p>{@link #phone} 必须是<b>已脱敏</b>的形式（{@code TextUtil.maskPhone}）：
 * 脱敏口径由实现方守住，接口注释里写清，因为后台一旦流出手机号，泄露面是一整张用户表。
 * 同理这里<b>不含密码、不含登录 IP、不含设备指纹</b>——一期用不到，就不给它们一条通往后台的路径。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 登录账号 */
    private String account;

    /** 昵称 */
    private String nickname;

    /** 头像地址，可为空 */
    private String avatar;

    /** 脱敏后的手机号，未绑定为空 */
    private String phone;

    /** 账号状态：1 正常 0 禁用（与 {@code im_user.status} 同口径） */
    private Integer status;

    /** 当前是否有活跃 WebSocket 连接（近似「在线」，与聊天在线判定同一个来源） */
    private Boolean online;

    /** 积分余额（虚拟积分，不可提现） */
    private Long balance;

    /** 注册时间 */
    private LocalDateTime createTime;

    /** 是否超级管理员：后台据此拒绝「封掉同行」这类自伤操作 */
    private Boolean admin;
}
