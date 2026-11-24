package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 交友卡片，跨模块传输用：im-friend 的滑动匹配、附近的人、访客、动态都要展示「这个人是谁」，
 * 但 {@code im_user_profile} / {@code im_user_photo} / {@code im_user_tag} 三张表都归 im-user。
 *
 * <p>星型架构下 im-friend 读不到那些表，所以由 im-user 把卡片拼好再交出来，而不是让调用方
 * 为了一个年龄去反射三条 SQL——那样隐私收口（{@code show_age}、距离模糊化）就会被复制到各个模块，
 * 漏一处就是一处泄露。收口只发生在 {@code DatingQuerySpiImpl} 一处。
 *
 * <p><b>刻意没有的字段</b>：经纬度（只给 {@link #distanceText} 那一句模糊文案）、
 * 审核中的照片（{@link #coverUrl} 只会是已审核通过的那一张）、手机号与邮箱。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友卡片（跨模块）")
public class DatingCardDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "头像地址")
    private String avatar;

    @Schema(description = "性别：0 未知 1 男 2 女")
    private Integer gender;

    @Schema(description = "个性签名")
    private String signature;

    @Schema(description = "年龄；未填或对方隐藏年龄时为 null（不是 0）")
    private Integer age;

    @Schema(description = "身高 cm，未填为 null")
    private Integer heightCm;

    @Schema(description = "职业")
    private String occupation;

    @Schema(description = "学历文案，未填空串")
    private String educationDesc;

    @Schema(description = "收入区间文案，未填空串")
    private String incomeDesc;

    @Schema(description = "婚恋状况文案，未填空串")
    private String marriageDesc;

    @Schema(description = "交友目的文案，未填空串")
    private String datingGoalDesc;

    @Schema(description = "城市级展示文本，如「杭州 · 西湖区」")
    private String locationText;

    @Schema(description = "与查看者的模糊距离；任一方定位过期或对方关了距离显示时为 null")
    private String distanceText;

    @Schema(description = "封面照地址：主封面优先，被驳回时自动退回下一张已通过的照片；一张都没有则 null")
    private String coverUrl;

    @Schema(description = "审核通过的照片张数，卡片上「3 张照片」那行用")
    private Integer photoCount;

    @Schema(description = "兴趣标签名，按 TA 自己的排序")
    private List<String> tagNames;

    @Schema(description = "完资料度 0~100")
    private Integer profileScore;

    @Schema(description = "是否在线；已按对方的 show_online 收口，陌生人位可能恒为 false")
    private Boolean online;

    /**
     * 推荐流给这张卡的理由文案（「TA 喜欢了你」/「最近见过 TA」），其余读法与没有理由时为 null。
     *
     * <p>为什么是一个文案字段而不是几个布尔位：理由的两个组成（互惠 / 重逢）全住在
     * im-friend 的表里，而卡片是 im-user 拼的。摊成 {@code reciprocal} + {@code reExposed} 两个位
     * 就等于把推荐流的分区口径复制一份到前端去拼，而下一次调分区只会多一个对不上的地方。
     * 前端只渲染不判断；为 null 就不显示。
     *
     * <p><b>写入方只有一个：im-friend</b>（{@code MatchServiceImpl#deck}）。新鲜度不在这里：
     * 注册时间在 im-user 手里，但那是 {@code sortBy=new} 的<b>排序依据</b>而不是需要向用户解释的事，
     * 为一个文案多开一个写入方不值。
     */
    @Schema(description = "推荐理由文案，只有推荐流（卡池）会填；其他入口与没有理由时为 null")
    private String matchReason;
}
