package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 抢红包的结果：抢到了多少 + 抢完之后的余额。
 *
 * <p>只回这两个数就够：金额用来弹「手气如何」，余额用来刷新钱包；
 * 领取明细由前端随后调 {@code GET /api/wallet/packet/{packetId}} 现取，
 * 不必在抢的瞬间把全名单塞进这个响应里（群红包几十人，纯属浪费）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "抢红包结果")
public class PacketClaimVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "红包 ID")
    private Long packetId;

    @Schema(description = "本次抢到的积分")
    private long amount;

    @Schema(description = "入账后的余额")
    private long balance;

    @Schema(description = "已领份数（含本次）")
    private int claimedCount;

    @Schema(description = "总份数，用于判断是否最后一个")
    private int totalCount;
}
