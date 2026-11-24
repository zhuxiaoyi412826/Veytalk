package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 滑动动作入参。
 *
 * <p>只给 {@code targetUserId} 而不给「卡片 ID」：卡片本身不是实体，滑动这条动作的幂等键
 * 天然就是 {@code (from, to)} 这一对（{@code uk_from_to}），带一个易变的卡片 ID 进来只会多出
 * 一种「前端缓存的卡片 ID 已经过期」的失败模式。
 */
@Data
@Schema(description = "滑动匹配动作入参")
public class MatchActionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "目标用户 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1002")
    @NotNull(message = "targetUserId 不能为空")
    private Long targetUserId;

    @Schema(description = "动作：1 喜欢 2 跳过（把已经点过的喜欢收回去也是传 2）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "action 不能为空")
    @Min(value = 1, message = "action 只能是 1 喜欢或 2 跳过")
    @Max(value = 2, message = "action 只能是 1 喜欢或 2 跳过")
    private Integer action;

    @Schema(description = "来源场景：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好；不传按 1", example = "1")
    @Min(value = 1, message = "source 取值范围为 1-5")
    @Max(value = 5, message = "source 取值范围为 1-5")
    private Integer source;
}
