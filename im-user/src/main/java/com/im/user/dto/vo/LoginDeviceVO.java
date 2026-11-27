package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 「设置 · 登录设备」列表里的一行。
 *
 * <p>★ 只回给设备的主人：这里的 IP、UA、登录时间合起来就是「这个人什么时候在哪儿上过线」，
 * 属于最不该漏给别人的那类数据。因此本 VO 只出现在 {@code /api/device/list}（当前登录用户自查），
 * 不进任何 SPI 契约、不给后台列表复用（后台要看别人的设备，得单独开一套带审计的接口）。
 *
 * <p>{@link #deviceName} 是按「上送友好名 → UA 里的可读片段 → 设备类型中文名」三级兜底算出来的，
 * 所以它总有一个能看的值；但它是<b>客户端自己填的</b>，只能当标签用，不能拿去做任何判定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录设备")
public class LoginDeviceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "设备记录 ID，撤销接口按它定位")
    private Long id;

    @Schema(description = "设备类型码：web / pc / android / ios / mini")
    private String deviceType;

    @Schema(description = "设备类型中文名，可直接展示")
    private String deviceTypeDesc;

    @Schema(description = "展示用设备名（友好名 → UA → 类型中文名 三级兜底）")
    private String deviceName;

    @Schema(description = "最近一次登录的客户端 IP；未接归属地库，因此只有 IP，没有城市")
    private String ip;

    @Schema(description = "该设备累计登录次数")
    private Integer loginCount;

    @Schema(description = "最近登录时间")
    private LocalDateTime lastLoginTime;

    @Schema(description = "最近下线时间，从未正常下线过则为空")
    private LocalDateTime lastLogoutTime;

    @Schema(description = "状态：1 在用 0 已撤销")
    private Integer status;

    @Schema(description = "是否就是当前正在用的这台设备（为 true 时前端应禁用「撤销」）")
    private boolean current;

    @Schema(description = "当前是否在线（按 Redis 在线键判定，与好友列表的绿点同源）")
    private boolean online;

    /**
     * 后端能不能把这台设备单独定位出来。
     *
     * <p>为 false 表示这是老客户端登记的行（只有类型码、没有实例 ID）：撤销它会把该类型下
     * 所有老客户端会话一起下线，前端应当提前把这句话讲清楚，而不是让用户以为只关掉了一台。
     */
    @Schema(description = "是否能单独定位这台设备；false 表示老客户端只上送了类型码")
    private boolean identifiable;
}
