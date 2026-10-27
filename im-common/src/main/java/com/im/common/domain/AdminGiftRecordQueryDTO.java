package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 后台送礼流水的查询条件，由 im-admin 组装、im-live 执行（{@code AdminLiveSpi#pageGiftRecords}）。
 *
 * <p>为什么不放在 {@code AdminWalletQueryDTO} 里一起给：{@code im_gift_record} 归 im-live，
 * {@code im_wallet_transaction} 与 {@code im_gift} 归 im-user，星型架构下这两张表不能由同一个
 * 实现方读。钱包那一屏把它们做成两个页签，接口也照样分属两个 SPI。
 *
 * <p>索引现状决定用法：这张表有 {@code idx_room_time} 与 {@code idx_receiver_time}，
 * 所以按房间、按主播查是便宜的；<b>{@code senderId} 没有任何索引能命中</b>
 * （只能撞主键或全表扫）。送礼表是「账本性质只增不改」，量会随场次线性涨，
 * 因此这一维现在靠 {@code (create_time, id)} 主键倒扫 + 单页上限 50 兜着，
 * 真要常用就补一条 {@code idx_sender_time (sender_id, create_time)}——那是未来的一次 DDL，
 * 本批（零表结构变更）不做，代价与判据写在开发指南的验证清单里。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGiftRecordQueryDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 限定某个直播间 */
    private Long roomId;

    /** 限定某个收礼主播 */
    private Long receiverId;

    /** 限定某个送礼人（无索引可用，见类注释） */
    private Long senderId;

    /** 限定某件礼物（{@code gift_id} 也没索引，同上） */
    private Long giftId;

    /** 时间范围起点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 时间范围终点（含） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;

    /** 页码，从 1 开始 */
    private long current;

    /** 每页条数，实现方夹到 50 */
    private long size;
}
