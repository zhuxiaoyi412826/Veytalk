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
 * 「设置 · 登录记录」列表里的一行，数据来自 {@code im_login_log}。
 *
 * <p>给用户看这一屏的意义不在好看，而在<b>可核对</b>：账号被盗的第一手证据通常是
 * 「这条记录里的设备我不认识 / 这个 IP 我没用过」。所以 {@link #eventDesc} 与 {@link #detail}
 * 必须写成一句人话（「登录设备被同端新登录顶下线 · 被 Chrome on Windows 顶替」），
 * 而不是留一个数字让用户去猜。
 *
 * <p>★ 只回给本人，理由同 {@link LoginDeviceVO}。保留期由
 * {@code im.account.login-log-keep-days} 控制，超期的行已被物理删除，列表会自然变短。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录与设备变动记录")
public class LoginRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "记录 ID（雪花，时间倒序排序键）")
    private Long id;

    @Schema(description = "事件码：1 登录成功 2 登录失败 3 注销 4 同端顶下线 5 被后台踢下线 6 撤销设备 7 改绑凭证全端下线")
    private Integer event;

    @Schema(description = "事件文案，可直接展示")
    private String eventDesc;

    @Schema(description = "设备类型码")
    private String deviceType;

    @Schema(description = "设备类型中文名")
    private String deviceTypeDesc;

    @Schema(description = "展示用设备名")
    private String deviceName;

    @Schema(description = "客户端 IP；未接归属地库，因此只有 IP，没有城市")
    private String ip;

    @Schema(description = "补充说明：失败原因 / 顶掉它的那台设备名 / 操作来源")
    private String detail;

    @Schema(description = "发生时间")
    private LocalDateTime createTime;
}
