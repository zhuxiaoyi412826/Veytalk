package com.im.friend.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 本人的筛选偏好。
 *
 * <p>没保存过偏好时后端<b>不会凭空插一行</b>，而是回一组出厂默认值并带上 {@link #saved} = false：
 * 一行不存在的偏好与一行出厂默认的偏好在业务上等价，但前者不该为一次「打开弹层看看」就产生写操作。
 *
 * <p>学历与婚恋状况除了 code 还给一句文案（同 {@code sortByDesc}）：这两个 chip 在交友页、
 * 弹层里都要渲，而 code→文案的映射只应该有一个出处（im-common 的枚举）。让前端自己拉字典
 * 再反查，就是把同一份映射抄第二遍。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友筛选偏好")
public class PreferenceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否已经保存过自己的偏好（false = 下面全是出厂默认值）")
    private Boolean saved;

    @Schema(description = "对方性别：0 不限 1 男 2 女")
    private Integer targetGender;

    @Schema(description = "年龄下限（含）")
    private Integer ageMin;

    @Schema(description = "年龄上限（含）")
    private Integer ageMax;

    @Schema(description = "距离上限 km：0 不限；只作用于附近的人")
    private Integer distanceKm;

    @Schema(description = "只看指定城市（包含匹配）；空串 = 不限")
    private String targetCity;

    @Schema(description = "身高下限 cm：0 不限")
    private Integer heightMin;

    @Schema(description = "身高上限 cm：0 不限")
    private Integer heightMax;

    @Schema(description = "最低学历：0 不限")
    private Integer targetEducation;

    @Schema(description = "最低学历的展示文案（如「本科」，「及以上」这三个字由前端拼）；0 不限时为空串")
    private String targetEducationDesc;

    @Schema(description = "婚恋状况：0 不限")
    private Integer targetMarriage;

    @Schema(description = "婚恋状况的展示文案；0 不限时为空串")
    private String targetMarriageDesc;

    @Schema(description = "在线优先：1 是 0 否")
    private Integer onlineFirst;

    @Schema(description = "排序口径：recommend / active / distance（hot、new 属后面那批）")
    private String sortBy;

    @Schema(description = "排序口径的展示文案，前端 chip 直接用，省得再抄一份映射")
    private String sortByDesc;

    @Schema(description = "是否排除已跳过的人：1 排除 0 允许重新出现")
    private Integer excludePassed;
}
