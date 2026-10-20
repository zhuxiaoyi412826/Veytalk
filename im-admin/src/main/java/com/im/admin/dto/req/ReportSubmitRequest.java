package com.im.admin.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 提交举报入参。
 *
 * <p><b>没有 reporterId 这个字段</b>：举报人只能取自登录态，任何从请求体里传进来的举报人都是可伪造的，
 * 而伪造的举报人会让配额与去重双双失效（脚本可以换着 ID 无限报）。
 *
 * <p>{@code reasonCode} 只收码串、{@code reasonText} 收自由文本，两者分工见 {@code ReportReason}：
 * 分类必须可聚合，描述可以随便写。校验（码是否存在、该码是否要求描述、长度截断、敏感词遮罩）
 * 全在 service 里做，注解只管「非空」这一层——注解里写死枚举值会让加理由变成两头改。
 */
@Data
@Schema(description = "提交举报入参")
public class ReportSubmitRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "被举报对象类型：user / message / room / group",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "举报对象类型不能为空")
    private String targetType;

    @Schema(description = "被举报对象 ID（按类型解释：用户 ID / 消息 ID / 房间 ID / 群 ID）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "举报对象 ID 不能为空")
    private Long targetId;

    @Schema(description = "举报理由码：porn / abuse / violence / fraud / spam / infringement / minor / other",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请选择举报理由")
    private String reasonCode;

    @Schema(description = "补充描述，可选；标注为必填的理由不写会被拒绝")
    private String reasonText;
}
