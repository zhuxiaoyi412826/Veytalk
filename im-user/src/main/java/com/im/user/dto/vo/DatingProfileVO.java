package com.im.user.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * 交友资料卡。同一个 VO 服务「看自己」和「看别人」两种场景，差异由字段是否为空表达：
 *
 * <ul>
 *   <li>隐私开关、完资料度、待补提示<b>只在自己那侧返回</b>——别人不需要知道你把年龄藏了；</li>
 *   <li>年龄受 {@code showAge} 收口，为 {@code null} 时前端整行不渲染（不是显示 0 岁）；</li>
 *   <li>距离只有模糊化后的文案，<b>永远没有经纬度</b>：精确坐标一经下发就收不回来。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友资料卡")
public class DatingProfileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "是否为本人视角：true 时隐私字段与待补提示才会返回")
    private Boolean self;

    @Schema(description = "出生日期；对方隐藏年龄时为 null")
    private LocalDate birthday;

    @Schema(description = "按当天算出的周岁年龄；未填或隐藏时为 null")
    private Integer age;

    @Schema(description = "身高 cm")
    private Integer heightCm;

    @Schema(description = "学历 code")
    private Integer education;

    @Schema(description = "学历文案，未填为空串")
    private String educationDesc;

    @Schema(description = "职业")
    private String occupation;

    @Schema(description = "收入区间 code")
    private Integer income;

    @Schema(description = "收入区间文案，未填为空串")
    private String incomeDesc;

    @Schema(description = "婚恋状况 code")
    private Integer marriage;

    @Schema(description = "婚恋状况文案，未填为空串")
    private String marriageDesc;

    @Schema(description = "交友目的 code")
    private Integer datingGoal;

    @Schema(description = "交友目的文案，未填为空串")
    private String datingGoalDesc;

    @Schema(description = "省份")
    private String province;

    @Schema(description = "城市")
    private String city;

    @Schema(description = "区县")
    private String district;

    @Schema(description = "城市级展示文本，如「上海市 黄浦区」；一个字段都没填时为空串")
    private String locationText;

    @Schema(description = "与对方的模糊距离（如「500 m 内」「3 km」）；任一方无有效定位或对方关了距离显示时为 null")
    private String distanceText;

    @Schema(description = "本人定位是否在采信期内（超 7 天的坐标不再采用）；仅本人视角返回")
    private Boolean locationFresh;

    @Schema(description = "是否已经采集过坐标；仅本人视角返回。坐标本身一律不下发，前端只拿它判断要不要显示「已定位」")
    private Boolean hasLocation;

    @Schema(description = "动态可见范围偏好：0 全部 3 三天 7 一周 30 一个月；仅本人视角返回")
    private Integer momentVisibleDays;

    @Schema(description = "是否显示年龄；仅本人视角返回")
    private Integer showAge;

    @Schema(description = "是否显示距离；仅本人视角返回")
    private Integer showDistance;

    @Schema(description = "是否对陌生人显示在线状态；仅本人视角返回")
    private Integer showOnline;

    @Schema(description = "是否出现在推荐流/附近的人；仅本人视角返回")
    private Integer discoverable;

    @Schema(description = "完资料度 0~100")
    private Integer profileScore;

    @Schema(description = "还缺哪些（按加分从高到低），引导用户把资料补全；仅本人视角返回")
    private List<String> missingTips;
}
