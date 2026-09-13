package com.im.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.im.common.enums.WsMessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * WebSocket 统一报文结构，上下行共用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "WebSocket 报文")
public class WsPacket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "报文类型，见 WsMessageType")
    private String type;

    @Schema(description = "客户端消息 ID，回执时原样带回用于本地消息对齐")
    private String clientMsgId;

    @Schema(description = "业务数据")
    private Object data;

    @Schema(description = "错误码，仅 error 报文携带")
    private Integer code;

    @Schema(description = "错误描述，仅 error 报文携带")
    private String message;

    @Schema(description = "服务端时间戳（毫秒）")
    private long timestamp;

    public static WsPacket of(WsMessageType type, Object data) {
        return WsPacket.builder()
                .type(type.getType())
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static WsPacket of(WsMessageType type, String clientMsgId, Object data) {
        return WsPacket.builder()
                .type(type.getType())
                .clientMsgId(clientMsgId)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static WsPacket pong() {
        return of(WsMessageType.PONG, System.currentTimeMillis());
    }

    public static WsPacket error(String clientMsgId, int code, String message) {
        return WsPacket.builder()
                .type(WsMessageType.ERROR.getType())
                .clientMsgId(clientMsgId)
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
