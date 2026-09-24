package com.im.remote.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 远程控制文本帧信封：{@code {v, type, sid, seq, ts, data}}。
 *
 * <p>中继不反序列化 data（两端业务语义自解析），只消费 type/sid 做路由，
 * 所以 data 用 Map 承载即可——转发时把收到的原始 JSON 字符串直接发出去，
 * 这里提供的构造方法仅用于服务端自己发起的控制帧。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RemoteEnvelope implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 协议版本 */
    private int v;

    /** 帧类型，见 {@link RemoteProtocol} */
    private String type;

    /** 所属远程会话 ID，设备级控制帧（auth/ready/ping）为空 */
    private Long sid;

    /** 发送方自增序号，回执帧用它对齐请求 */
    private Long seq;

    /** 发送方毫秒时间戳 */
    private Long ts;

    /** 业务数据 */
    private Map<String, Object> data;

    public static RemoteEnvelope of(String type, Map<String, Object> data) {
        return build(type, null, data);
    }

    public static RemoteEnvelope of(String type, Long sid, Map<String, Object> data) {
        return build(type, sid, data);
    }

    private static RemoteEnvelope build(String type, Long sid, Map<String, Object> data) {
        return RemoteEnvelope.builder()
                .v(1)
                .type(type)
                .sid(sid)
                .ts(System.currentTimeMillis())
                .data(data)
                .build();
    }
}
