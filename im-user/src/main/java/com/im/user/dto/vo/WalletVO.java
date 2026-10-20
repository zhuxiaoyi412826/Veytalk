package com.im.user.dto.vo;

import com.im.common.domain.GiftBriefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 钱包视图：一次拉回余额 + 礼物目录 + 前端渲染礼簿/充值面板所需的配置。
 *
 * <p>把 {@code checkinBonus}/{@code rechargeMin}/{@code rechargeMax} 一并下发，是为了让前端的签到提示
 * 与充值金额校验跟服务端用同一份配置，而不是前端再硬编码一套——两边漂移了就会出现「前端能提交、后端拒收」。
 */
@Data
@Builder
@Schema(description = "钱包视图")
public class WalletVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "钱包功能是否开启，关闭时前端隐藏礼簿与充值入口")
    private boolean enabled;

    @Schema(description = "当前积分余额")
    private long balance;

    @Schema(description = "上架礼物目录，按 sort 升序")
    private List<GiftBriefDTO> gifts;

    @Schema(description = "每日签到赠送积分")
    private long checkinBonus;

    @Schema(description = "今日是否已签到")
    private boolean signedToday;

    @Schema(description = "单笔充值积分下界")
    private long rechargeMin;

    @Schema(description = "单笔充值积分上界")
    private long rechargeMax;

    /**
     * 以下是「用户之间转积分」的口径，一并下发给前端的原因与 {@code rechargeMin} 相同：
     * 让输入框的校验与服务端用同一份配置，避免「前端能提交、后端拒收」。其中
     * {@code transferEnabled} 独立于 {@code enabled}：平台可以保留送礼与充值，只关掉转账/红包。
     */
    @Schema(description = "转账与红包是否开放（独立于钱包总开关）")
    private boolean transferEnabled;

    @Schema(description = "单笔转账积分下界，同时是红包总额下界")
    private long transferMin;

    @Schema(description = "单笔转账积分上界")
    private long transferMax;

    @Schema(description = "单个红包总额上界")
    private long packetMax;

    @Schema(description = "单个红包份数上限（群聊拼手气）")
    private int packetMaxCount;

    @Schema(description = "红包无人领取时的保留时长（小时），到期自动退回")
    private int packetExpireHours;
}
