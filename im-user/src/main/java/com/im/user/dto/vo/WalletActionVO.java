package com.im.user.dto.vo;

import com.im.common.domain.MessageDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 资金动作的结果：新余额 + 那条已经落库的资金消息（转账 / 发红包共用）。
 *
 * <p>为什么要把消息体一起返回：转账与红包都是「服务端内部类型」，客户端不能自己 POST 出来，
 * 于是这次 REST 调用就是这条消息唯一的产生点。不返回它，前端就只能等 WebSocket 回放，
 * 而本机其他标签页/端收到推送是一回事、<b>当前这一端</b>要不要立刻上屏是另一回事——
 * 靠 ack 镜像上屏会被「多端共享」开关挡掉，用户在关了那个开关的端上点了转账就会看到「毫无反应」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "资金动作结果（余额 + 消息）")
public class WalletActionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "动作完成后的余额，前端可直接刷新本地钱包快照")
    private long balance;

    @Schema(description = "已落库并广播出去的转账/红包消息，前端就地追加到会话")
    private MessageDTO message;
}
