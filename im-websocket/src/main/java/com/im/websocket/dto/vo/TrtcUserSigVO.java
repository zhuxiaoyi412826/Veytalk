package com.im.websocket.dto.vo;

import lombok.Data;

/**
 * 微信小程序 TRTC 音视频通话的 UserSig 下发体，对应 {@code GET /api/call/trtc-user-sig}。
 *
 * <p>只回三样：{@code sdkAppId} / {@code userId} / {@code userSig}。<b>SecretKey 只在服务端参与签名，
 * 任何情况下都不出现在本响应里</b>。未配置 TRTC 时 {@code sdkAppId=0}、{@code userSig} 为空串（这不是错误），
 * 前端据此禁用通话入口——与 {@code im.call.enabled} 的可用性降级口径一致。
 */
@Data
public class TrtcUserSigVO {

    /** 腾讯云 TRTC 的 SDKAppID；0 表示后端未配置，前端应禁用通话 */
    private long sdkAppId;

    /** 当前登录用户的雪花 ID（字符串形式），与全链路 userid 口径一致 */
    private String userId;

    /** 现签的 UserSig；未配置时为空串 */
    private String userSig;
}
