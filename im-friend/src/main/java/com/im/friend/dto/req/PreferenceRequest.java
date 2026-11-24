package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 保存筛选偏好入参。
 *
 * <p><b>语义是整组替换，不是「null 字段不修改」</b>（与 {@code PUT /dating/profile} 相反）：
 * 弹层里提交的是一张完整表单，「把年龄上限从 45 改成不限」这件事只能用「显式交回 0 / null」表达，
 * 局部更新语义下它会被读成「这次不改这一项」而永远改不掉。同理，字段越界一律报错而不是夹紧
 * （夹紧会让一个写错的控件值静默变成另一个筛选条件）。
 */
@Data
@Schema(description = "保存交友筛选偏好")
public class PreferenceRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "对方性别：0 不限 1 男 2 女；不传按 0", example = "0")
    @Min(value = 0, message = "targetGender 只能是 0 不限 / 1 男 / 2 女")
    @Max(value = 2, message = "targetGender 只能是 0 不限 / 1 男 / 2 女")
    private Integer targetGender;

    @Schema(description = "年龄下限 18-80；不传按 18（未成年人不进交友池）", example = "22")
    @Min(value = 18, message = "年龄下限不能小于 18 岁")
    @Max(value = 80, message = "年龄下限不能大于 80 岁")
    private Integer ageMin;

    @Schema(description = "年龄上限 18-80，且不小于年龄下限；不传按 45", example = "35")
    @Min(value = 18, message = "年龄上限不能小于 18 岁")
    @Max(value = 80, message = "年龄上限不能大于 80 岁")
    private Integer ageMax;

    @Schema(description = "距离上限 km：0 不限，1-20；不传按 0。只作用于「附近的人」", example = "5")
    @Min(value = 0, message = "distanceKm 最小为 0（不限）")
    @Max(value = 20, message = "distanceKm 最大为 20，再远请用城市筛选")
    private Integer distanceKm;

    @Schema(description = "只看指定城市（包含匹配，如「杭州」也能命中「杭州市」）；空 = 不限", example = "杭州")
    @Size(max = 32, message = "城市名最长 32 个字符")
    private String targetCity;

    @Schema(description = "身高下限 cm：0 不限，100-250；不传按 0", example = "165")
    @Min(value = 0, message = "身高下限只能是 0（不限）或 100-250")
    @Max(value = 250, message = "身高下限只能是 0（不限）或 100-250")
    private Integer heightMin;

    @Schema(description = "身高上限 cm：0 不限，100-250，且不小于身高下限；不传按 0", example = "0")
    @Min(value = 0, message = "身高上限只能是 0（不限）或 100-250")
    @Max(value = 250, message = "身高上限只能是 0（不限）或 100-250")
    private Integer heightMax;

    @Schema(description = "最低学历，同交友字典 education 的取值（本科为 4，按「及以上」比较）；0 不限", example = "3")
    @Min(value = 0, message = "最低学历取值范围为 0-6")
    @Max(value = 6, message = "最低学历取值范围为 0-6")
    private Integer targetEducation;

    @Schema(description = "婚恋状况，同交友字典 marriage 的取值（等值匹配）；0 不限", example = "1")
    @Min(value = 0, message = "婚恋状况取值范围为 0-4")
    @Max(value = 4, message = "婚恋状况取值范围为 0-4")
    private Integer targetMarriage;

    @Schema(description = "在线优先：1 把在线的人排在前面 0 不按在线重排", example = "1")
    @Min(value = 0, message = "onlineFirst 只能是 0 或 1")
    @Max(value = 1, message = "onlineFirst 只能是 0 或 1")
    private Integer onlineFirst;

    @Schema(description = "排序口径：recommend 综合 / active 活跃优先 / distance 离我最近（hot、new 属推荐流那批）",
            example = "recommend")
    @Size(max = 16, message = "sortBy 最长 16 个字符")
    private String sortBy;

    @Schema(description = "是否排除已跳过的人：1 排除（默认） 0 允许重新出现", example = "1")
    @Min(value = 0, message = "excludePassed 只能是 0 或 1")
    @Max(value = 1, message = "excludePassed 只能是 0 或 1")
    private Integer excludePassed;
}
