package com.im.ai.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 结束面试请求。
 *
 * <p>{@code reason} 分得这么细是有原因的：审计看的是「这人是正常答完的，还是中途跑的」。
 * reload 与 close 都算未完成，但在监考上意义不同——刷新一次就把面试重来，
 * 本身就说明页面状态没保住，需要跟候选人确认。
 *
 * <p>违规达阈值时前端也会调这里，但那时会话在服务端已经被强制结束了，
 * 这一次调用只是把 end_reason 保持成 violation-limit 而不是被 user-end 顶掉，
 * 因此结束接口做成幂等的。
 */
@Data
@Schema(description = "结束 AI 面试")
public class InterviewEndRequest {

    @NotNull(message = "面试会话 ID 不能为空")
    @Schema(description = "面试会话 ID")
    private Long sessionId;

    @Pattern(regexp = "user-end|reload|close|timeout|violation-limit", message = "结束原因取值不合法")
    @Schema(description = "结束原因：user-end 自行结束 / reload 刷新 / close 离开页面 / timeout 超时 / violation-limit 违规达上限")
    private String reason;
}
