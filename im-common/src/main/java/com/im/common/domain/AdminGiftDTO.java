package com.im.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 礼物目录里的一件礼物（{@code im_gift}），后台视角。
 *
 * <p>后台要的这一份<b>含已下架</b>：用户端礼簿只认 {@code status=1}，
 * 而管理员问的是「这个礼物为什么在客户端不见了」，只看得到上架的那一半就答不了。
 *
 * <p>本类<b>不带送出次数与收礼总额</b>：那两个数在 {@code im_gift_record}，归 im-live 持有。
 * 星型架构下每张表只在归属模块映射一次，im-user 不能为了凑一列好看的统计去读别人的表。
 * 要看流水，用同一屏的「送礼流水」页签（{@link AdminGiftRecordDTO}，由 im-live 提供），
 * 那里按礼物 ID 筛就是同一件事的另一面。
 *
 * <p>{@code price} 改动<b>不回溯历史</b>：送礼时把单价冗余进了 {@code im_gift_record.unit_price}，
 * 所以改价只影响之后的送礼，历史对账不受影响。也正因为改价会立刻改变用户实付额，
 * 后台这一屏只提供上下架，不提供改价与增删——那属于运营开新礼物的动作，
 * 现在改一行 SQL 就能做，真要做成界面应当连图标素材上传一起做，而不是塞进这个表格的编辑框里。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AdminGiftDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 礼物 ID */
    private Long giftId;

    /** 礼物名 */
    private String name;

    /** 图标：表情符号或图片地址，两样都可能有 */
    private String icon;

    /** 单价（积分） */
    private Integer price;

    /** 类型：normal 普通 / effect 特效 */
    private String kind;

    /** 排序值，小的在前 */
    private Integer sort;

    /** 是否上架：1 上架 0 下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后改动时间（上下架也会拨动它） */
    private LocalDateTime updateTime;
}
