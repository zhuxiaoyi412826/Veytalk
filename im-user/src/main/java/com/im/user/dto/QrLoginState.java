package com.im.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 扫码登录在 Redis 里的一次会话状态，JSON 存于 {@code im:qrlogin:{scene}}，TTL 120 秒。
 *
 * <p>这是纯服务端内部结构，不直接返回给前端（对外由 {@code QrLoginStatusVO} 收敛，
 * 尤其 {@code userId} 绝不能下发）。{@code status} 语义见 {@code QrLoginStatusVO}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QrLoginState implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 0 待扫码 / 1 已扫码待确认 / 2 已授权 */
    private int status;

    /** 授权成功后写入的登录用户 ID */
    private Long userId;

    /** 授权成功后为该用户新签的 web 设备 token */
    private String token;

    /**
     * 发起扫码那一端的设备位（{@code 类型:实例}），在 {@code create} 时由待登录的浏览器自己上送。
     *
     * <p>必须存在状态里而不是等 {@code authorize} 时再取：授权请求是<b>手机 APP</b> 发来的，
     * 那一刻的上下文里根本没有浏览器的设备信息，拿它去签会话就会把手机的身份误写成新端的身份，
     * 导致同一浏览器的多个页签共用一个裸类型码、将来想单独顶掉一台时顶不中。
     */
    private String deviceSlot;

    /** token 所在请求头名 */
    private String tokenName;

    /** token 有效期（秒） */
    private long expiresIn;

    /** 创建时间戳（毫秒），用于诊断 */
    private long createTime;
}
