package com.im.live.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 开播请求。
 *
 * <p>只收「房间信息」，不收任何媒体参数：分辨率与码率虽然也在这里声明，
 * 但它们只是给列表展示与带宽估算用的<b>标签</b>，真正生效的值写在推流端
 * ffmpeg 的命令行里。服务端不去校验两者是否一致——校验了也拦不住谎报，
 * 而谎报的后果（列表上写着 1080p 实际 720p）只影响展示，不值得为它加一道门禁。
 */
@Data
@Schema(description = "开播请求")
public class LiveStartRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房间标题", example = "周五晚：Spring Boot 4 新特性实战")
    @NotBlank(message = "房间标题不能为空")
    @Size(max = 60, message = "房间标题最多 60 字")
    private String title;

    @Schema(description = "封面图访问地址，可空")
    @Size(max = 512, message = "封面地址过长")
    private String cover;

    @Schema(description = "房间公告，进房时下发一次", example = "欢迎提问，弹幕请文明")
    @Size(max = 512, message = "公告最多 512 字")
    private String notice;

    @Schema(description = "推流源：screen 屏幕分享 / camera 摄像头", example = "screen")
    private String sourceType;

    @Schema(description = "分辨率档位标签", example = "720p")
    private String resolution;

    @Schema(description = "视频码率（kbps）", example = "2500")
    private Integer bitrateKbps;

    /**
     * 房间密码（可选）。非空即把本场锁成<b>密码直播</b>：观众要先输对密码才能拿到播放地址。
     * 服务端只存其 SHA-256 摘要（见 {@code LiveGateService}），不存明文，也绝不下发到任何列表/详情响应。
     */
    @Schema(description = "房间密码，留空为公开房；与 price 互斥（同时给按密码优先）", example = "1234")
    @Size(max = 32, message = "房间密码最多 32 位")
    private String password;

    /**
     * 入场价格（可选，单位：积分）。&gt;0 即把本场锁成<b>付费直播</b>：观众扣这么多积分才可入场。
     * 与 {@code gift} 同口径，是模拟积分、不进主播钱包不可提现；代价是本场入场费只在观众流水里留痕。
     * 给主播本人恒免（不扣自己）。
     */
    @Schema(description = "入场积分价，0/留空为免费；需钱包开启，与 password 互斥", example = "50")
    @jakarta.validation.constraints.Min(value = 0, message = "价格不能为负")
    private Long price;
}
