package com.im.live.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 送礼流水，对应表 {@code im_gift_record}，一行一笔送礼，账本性质只增不改。
 *
 * <p>继承 {@link AuditEntity}（无 {@code deleted} 列）：送礼是既成事实，不该被逻辑删除留下中间态。
 *
 * <p>刻意<b>冗余</b> {@code giftName}/{@code giftIcon}/{@code unitPrice} 三件礼物快照：礼物目录会随运营改价、
 * 下架甚至改名，而历史送礼记录必须展示「当时送的是什么、按什么价成交」，不能因为目录变了就对不上账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_gift_record")
public class GiftRecord extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 送礼人用户 ID */
    private Long senderId;

    /** 收礼主播用户 ID（即房间 anchorId） */
    private Long receiverId;

    /** 直播间 ID */
    private Long roomId;

    /** 礼物 ID */
    private Long giftId;

    /** 冗余：送礼时的礼物名 */
    private String giftName;

    /** 冗余：送礼时的礼物图标 */
    private String giftIcon;

    /** 数量 */
    private Integer count;

    /** 冗余：成交单价 */
    private Integer unitPrice;

    /** 总价 = unitPrice * count */
    private Long totalPrice;
}
