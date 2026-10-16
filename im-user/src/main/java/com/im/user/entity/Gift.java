package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 礼物目录实体，对应表 {@code im_gift}，运营可增删改，前端礼簿按 {@code status=1} 且 {@code sort} 升序渲染。
 *
 * <p>主键用雪花：种子数据（{@code sql/im_data.sql}）占用 5000~5999 固定 ID 段便于演示与清理，
 * 运营新增的礼物则由雪花发号，两段不冲突。
 *
 * <p>{@code price} 用 {@code Integer} 而不是 {@code Long}：单价是礼物本身的属性，量级很小，
 * 而一次送礼的总价（{@code price * count}）才可能溢出，那部分在 {@code im_gift_record.total_price} 用 BIGINT 承载。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_gift")
public class Gift extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：上架 */
    public static final int STATUS_ON = 1;
    /** 状态：下架 */
    public static final int STATUS_OFF = 0;

    /** 类型：普通 */
    public static final String KIND_NORMAL = "normal";
    /** 类型：特效 */
    public static final String KIND_EFFECT = "effect";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 图标（表情符号或图片地址） */
    private String icon;

    /** 单价（积分） */
    private Integer price;

    /** 类型：normal 普通 / effect 特效 */
    private String kind;

    /** 排序，小的在前 */
    private Integer sort;

    /** 状态：1 上架 0 下架 */
    private Integer status;
}
