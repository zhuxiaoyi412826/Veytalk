package com.im.user.dto.po;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 红包骨架：存在 Redis Hash 的 {@code meta} 字段里，一份红包的静态部分，创建后不再改动。
 *
 * <p>这是「领取态」而不是「账本」：钱在 {@code im_wallet_transaction} 里，这里只描述
 * 「这个红包是谁发的、总额多少、切成了几份、什么时候作废」。整份丢掉也不会丢钱，
 * 详见 {@code RedisKeys#WALLET_PACKET} 的口径说明。
 *
 * <p>{@code expireAt} 用 {@code long}（epochMilli）而不是 LocalDateTime：
 * 它只有一个用途——和 {@code System.currentTimeMillis()} 比大小，跨 Redis 序列化时越少一个类型越好。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "红包骨架")
public class PacketStatePO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 发送人用户 ID，也是过期退款的入账对象 */
    private Long senderId;

    /** 所在会话 ID：领取时用它复核成员身份，防止有人拿别人的 packetId 来抢 */
    private Long conversationId;

    /** 红包总额（积分），已从发送人余额真扣 */
    private Long totalAmount;

    /** 总份数，等于 {@code splits} 的长度 */
    private Integer totalCount;

    /** 祝福语（服务端清洗后定格） */
    private String greeting;

    /** 作废时间点（epochMilli）：过此时刻不再允许领取，退款由账本倒推完成 */
    private Long expireAt;
}
