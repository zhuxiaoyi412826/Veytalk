package com.im.live.dto.po;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 送礼流水的聚合结果载体（只读，不映射任何表）。
 *
 * <p>为什么单独一个类而不返回 {@code Map<String,Object>}：面板要按房间、按送礼人、按礼物种类
 * 三种维度分别聚合，字段各不相同，用 Map 会把列名当字符串散落在 Service 里，改一列就漏一处。
 *
 * <p><b>零 DDL</b>：三个维度都只读既有 {@code im_gift_record}，且分别命中它已建好的两个索引——
 * {@code idx_room_time(room_id, create_time)} 供按房间聚合、
 * {@code idx_receiver_time(receiver_id, create_time)} 供按主播聚合，不需要为面板新增索引或汇总表。
 */
public class GiftAggPO {

    private GiftAggPO() {
        // 仅作三个嵌套类的命名容器，不实例化
    }

    /** 按房间聚合：一场直播收了多少、多少人送过 */
    @Data
    public static class Room implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 直播间 ID（= {@code im_live_room.id}） */
        private Long roomId;

        /** 本场礼物总价值（积分） */
        private Long totalPrice;

        /** 本场送礼笔数（一笔 = 一条流水，同一人连送三次算三笔） */
        private Long recordCount;

        /** 本场送礼人数（去重） */
        private Long senderCount;
    }

    /** 按送礼人聚合：金主榜 */
    @Data
    public static class Gifter implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 送礼人用户 ID（昵称头像由 Service 经 {@code UserQuerySpi} 批量补齐，不在 SQL 里跨模块 join） */
        private Long senderId;

        /** 累计送出价值（积分） */
        private Long totalPrice;

        /** 累计送礼笔数 */
        private Long recordCount;

        /** 最后一次送礼时刻（面板上标「最近」用） */
        private LocalDateTime lastAt;
    }

    /** 按礼物种类聚合：收到最多的礼物 */
    @Data
    public static class Type implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long giftId;

        /** 冗余列原样带出：礼物可能已下架改名，流水里的名字才是当时的样子 */
        private String giftName;

        private String giftIcon;

        /** 累计个数（{@code SUM(count)}，与「笔数」不同：一次送 10 个算 10 个） */
        private Long totalCount;

        /** 累计价值（积分） */
        private Long totalPrice;
    }
}
