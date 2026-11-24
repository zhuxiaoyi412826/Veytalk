package com.im.user.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 保存交友资料入参：字段为 {@code null} 表示<b>不修改</b>，要清空哪个字段就把它写进 {@link #clears}。
 *
 * <p>为什么要单独一个 {@code clears} 而不是「传 null 就是清空」：本接口与
 * {@code UpdateProfileRequest} 一样走「只更新非 null 字段」的部分更新语义，
 * 而前端日期控件被用户点掉之后拿到的就是 {@code null}——两种 null 分不开，
 * 结果要么是「清空没生效」，要么是「只改了一个字段就把其余全抹掉」。
 */
@Data
@Schema(description = "保存交友资料请求（null 字段不修改，清空走 clears）")
public class DatingProfileRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "出生日期（必须早于今天）", example = "1995-06-12")
    @Past(message = "出生日期必须早于今天")
    private LocalDate birthday;

    @Schema(description = "身高 cm", example = "163")
    @Min(value = 100, message = "身高需在 100~250 cm 之间")
    @Max(value = 250, message = "身高需在 100~250 cm 之间")
    private Integer heightCm;

    @Schema(description = "学历：0 未填 1 初中及以下 2 高中/中专 3 大专 4 本科 5 硕士 6 博士", example = "4")
    @Min(value = 0, message = "学历取值超出范围")
    @Max(value = 6, message = "学历取值超出范围")
    private Integer education;

    @Schema(description = "职业", example = "视觉设计师")
    @Size(max = 32, message = "职业最长 32 个字符")
    private String occupation;

    @Schema(description = "收入区间：0 未填 1 3千以下 2 3千~5千 3 5千~1万 4 1万~2万 5 2万~5万 6 5万以上", example = "4")
    @Min(value = 0, message = "收入区间取值超出范围")
    @Max(value = 6, message = "收入区间取值超出范围")
    private Integer income;

    @Schema(description = "婚恋状况：0 未填 1 未婚 2 单身离异 3 丧偶 4 不愿透露", example = "1")
    @Min(value = 0, message = "婚恋状况取值超出范围")
    @Max(value = 4, message = "婚恋状况取值超出范围")
    private Integer marriage;

    @Schema(description = "交友目的：0 未填 1 认真交往 2 先聊聊看 3 交朋友 4 找线下活动搭子", example = "1")
    @Min(value = 0, message = "交友目的取值超出范围")
    @Max(value = 4, message = "交友目的取值超出范围")
    private Integer datingGoal;

    @Schema(description = "所在省份", example = "上海市")
    @Size(max = 32, message = "省份最长 32 个字符")
    private String province;

    @Schema(description = "所在城市，同城筛选按它匹配", example = "上海市")
    @Size(max = 32, message = "城市最长 32 个字符")
    private String city;

    @Schema(description = "区县（只参与展示）", example = "黄浦区")
    @Size(max = 32, message = "区县最长 32 个字符")
    private String district;

    @Schema(description = "经度 WGS84，与纬度必须同时提供", example = "121.473700")
    @DecimalMin(value = "-180", message = "经度取值 -180~180")
    @DecimalMax(value = "180", message = "经度取值 -180~180")
    private BigDecimal lng;

    @Schema(description = "纬度 WGS84，与经度必须同时提供", example = "31.230400")
    @DecimalMin(value = "-90", message = "纬度取值 -90~90")
    @DecimalMax(value = "90", message = "纬度取值 -90~90")
    private BigDecimal lat;

    @Schema(description = "要清空的字段名，取值见 description；未知名称直接报错而不是静默忽略",
            example = "[\"heightCm\",\"location\"]",
            allowableValues = {"birthday", "heightCm", "education", "occupation", "income", "marriage",
                    "datingGoal", "province", "city", "district", "location"})
    @Size(max = 11, message = "clears 一次最多 11 项")
    private List<String> clears;
}
