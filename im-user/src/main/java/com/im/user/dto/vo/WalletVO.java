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
}
