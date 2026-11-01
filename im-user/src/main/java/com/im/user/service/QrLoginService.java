package com.im.user.service;

import com.im.user.dto.vo.QrLoginCreateVO;
import com.im.user.dto.vo.QrLoginStatusVO;

/**
 * 网页端扫码登录服务：网页出码 → APP 扫码 / 授权 → 网页轮询换取一次性登录态。
 *
 * <p>状态存 Redis（key {@code im:qrlogin:{scene}}，TTL 120 秒），不落库：这是一次短命的握手，
 * 没有持久化的必要，过期即自动清理。APP 侧扫码/授权由客户端实现，本服务只提供后端端点。
 */
public interface QrLoginService {

    /**
     * 生成一张新的扫码登录二维码：创建 scene（status=0）并落 Redis，二维码内容为网页回跳链接。
     *
     * @param base 网页站点根地址（origin），经清洗后拼进二维码链接
     * @return scene + base64 二维码 + 有效期
     */
    QrLoginCreateVO create(String base);

    /**
     * APP 扫码后调用：把 scene 从「待扫码」推进到「已扫码待确认」。
     *
     * @param scene 场景号
     */
    void markScanned(String scene);

    /**
     * APP 用户点确认授权后调用：以 {@code userId} 的身份新签一个 web 设备 token 写入状态并置为已授权。
     *
     * <p>userId 来自当前 APP 的登录态（由 Controller 传入），绝不从报文里取，防止越权替他人授权。
     *
     * @param scene  场景号
     * @param userId 授权的登录用户 ID
     */
    void authorize(String scene, Long userId);

    /**
     * 网页轮询状态：未到「已授权」只回状态；已授权则回一次性登录包并立即销毁 scene（防重放）。
     *
     * @param scene 场景号
     * @return 当前状态（status=2 时附带 token）
     */
    QrLoginStatusVO poll(String scene);
}
