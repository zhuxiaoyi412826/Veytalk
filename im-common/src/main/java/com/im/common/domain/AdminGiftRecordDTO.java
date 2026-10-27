package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一笔送礼流水（{@code im_gift_record}），后台视角，由 im-live 经 {@code AdminLiveSpi} 提供。
 *
 * <p>礼物名、图标、成交单价都是<b>送礼当刻的冗余快照</b>，不是联表查出来的当前值。
 * 这是有意的：礼物后来下架、改名、改价，历史流水必须还显示当时那一套，
 * 否则「他那天到底花了多少积分」这个问题就没有答案了。所以本类的
 * {@code giftName} 与目录里的名字有可能不一致，那属于正确而不是脏数据。
 *
 * <p>{@code roomTitle} 是这一屏唯一需要联表补的列（来自 {@code im_live_room.title}），
 * 房间行已被清理时留 null，前端显示成「房间 {roomId}」。不为了这一列去做视图或物化统计：
 * 一页最多 50 行，一次 {@code IN} 查询就够。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGiftRecordDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录 ID（雪花） */
    private Long recordId;

    /** 送礼人 ID */
    private Long senderId;

    /** 送礼人昵称（批量补，查不到给「已注销用户」） */
    private String senderName;

    /** 收礼主播 ID */
    private Long receiverId;

    /** 收礼主播昵称 */
    private String receiverName;

    /** 直播间 ID */
    private Long roomId;

    /** 直播间标题，房间行已不存在时为 null */
    private String roomTitle;

    /** 礼物 ID */
    private Long giftId;

    /** 送礼当刻的礼物名快照 */
    private String giftName;

    /** 送礼当刻的礼物图标快照 */
    private String giftIcon;

    /** 数量 */
    private Integer count;

    /** 成交单价快照 */
    private Integer unitPrice;

    /** 总价 = unitPrice * count（落库时算好，读侧不再乘一遍） */
    private Long totalPrice;

    /** 送礼时刻 */
    private LocalDateTime createTime;
}
