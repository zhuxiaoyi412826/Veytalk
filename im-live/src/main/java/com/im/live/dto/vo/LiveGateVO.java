package com.im.live.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 直播间门禁信息（随房间详情/列表下发给前端，用于渲染解锁弹窗）。
 *
 * <p>只描述「要不要解锁、要哪种解锁、多少钱」，<b>永不含密码本体或其摘要</b>——那是服务端
 * Redis 里的私有数据。前端据此决定：{@code type=none} 直接播；{@code password} 弹密码框走
 * {@code /unlock}；{@code paid} 弹确认付费走 {@code /pay}。
 */
@Data
@Schema(description = "直播门禁信息")
public class LiveGateVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 无门禁（公开房） */
    public static final String TYPE_NONE = "none";
    /** 密码房 */
    public static final String TYPE_PASSWORD = "password";
    /** 付费房 */
    public static final String TYPE_PAID = "paid";

    @Schema(description = "门禁类型：none 公开 / password 密码 / paid 付费")
    private String type;

    @Schema(description = "付费房的入场积分价；密码/公开房为空")
    private Long price;
}
