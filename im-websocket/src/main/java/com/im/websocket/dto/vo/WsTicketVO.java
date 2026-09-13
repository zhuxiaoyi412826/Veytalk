package com.im.websocket.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * WebSocket 连接票据。
 *
 * <p>前端拿到后拼成 {@code ws://host:8080/ws?ticket=xxx} 即可握手，
 * 不必把 7 天有效的登录 token 挂到 URL 上——URL 会进浏览器历史、进代理日志、进 Nginx access log，
 * 而一条 60 秒就失效的票据泄露了也换不来什么。
 */
@Data
@Builder
@Schema(description = "WebSocket 连接票据")
public class WsTicketVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "签名票据，握手时作为 ticket 查询参数携带")
    private String ticket;

    @Schema(description = "握手端点路径，与 host 和 ticket 拼接后即为完整连接地址", example = "/ws")
    private String endpoint;

    @Schema(description = "票据有效期（秒），必须在过期前完成握手；重连时要重新申请", example = "60")
    private long expiresIn;

    @Schema(description = "票据绑定的设备标识。服务端只认这个值，URL 上的 deviceId 参数会被忽略", example = "web")
    private String deviceId;
}
