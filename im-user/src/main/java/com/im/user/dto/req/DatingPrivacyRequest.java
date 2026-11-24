package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 交友资料隐私与可见性开关入参，字段为 {@code null} 表示不修改。
 *
 * <p>单独一个接口而不是塞进 {@link DatingProfileRequest}：这一组是「一键开关」的高频操作
 * （临出门把隐身打开），跟在一堆文本框后面提交，等于让最容易误触的开关和最麻烦的表单共用一次校验。
 */
@Data
@Schema(description = "交友资料隐私开关请求（null 字段不修改）")
public class DatingPrivacyRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否显示年龄：1 显示 0 隐藏（隐藏时资料卡整行留空，不显示 0 岁）", example = "1")
    @Min(value = 0, message = "只能为 0 或 1")
    @Max(value = 1, message = "只能为 0 或 1")
    private Integer showAge;

    @Schema(description = "是否显示距离：0 时仍参与「附近」排序，只是不显数字", example = "1")
    @Min(value = 0, message = "只能为 0 或 1")
    @Max(value = 1, message = "只能为 0 或 1")
    private Integer showDistance;

    @Schema(description = "是否对陌生人显示在线状态（好友与已配对不受影响）", example = "1")
    @Min(value = 0, message = "只能为 0 或 1")
    @Max(value = 1, message = "只能为 0 或 1")
    private Integer showOnline;

    @Schema(description = "是否出现在推荐流/附近的人/动态流：0 隐身（隐身期间去看别人也不会留下访客记录）；已有的好友与配对关系不受影响", example = "1")
    @Min(value = 0, message = "只能为 0 或 1")
    @Max(value = 1, message = "只能为 0 或 1")
    private Integer discoverable;

    @Schema(description = "动态可见范围偏好：0 全部 3 三天 7 一周 30 一个月", example = "0")
    @Min(value = 0, message = "动态可见范围只能为 0/3/7/30")
    @Max(value = 30, message = "动态可见范围只能为 0/3/7/30")
    private Integer momentVisibleDays;
}
