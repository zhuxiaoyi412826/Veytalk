package com.im.group.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 邀请码申请入群的结果回执。
 *
 * <p>{@code status} 只有两种取值：
 * <ul>
 *   <li>{@code joined}：目标群未开启入群审批，申请人已经直接落到成员表里；前端可以按 {@code conversationId} 跳聊天窗口</li>
 *   <li>{@code pending}：目标群开启了入群审批，申请已提交，等待管理员处理；{@code conversationId} 为空</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邀请码申请入群结果")
public class JoinApplyResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "结果状态：joined / pending", example = "joined")
    private String status;

    @Schema(description = "目标群 ID")
    private Long groupId;

    @Schema(description = "目标群名称")
    private String groupName;

    @Schema(description = "群会话 ID，status=joined 时可直接跳聊天窗口；status=pending 时为空")
    private Long conversationId;

    @Schema(description = "当前成员数（便于前端提示是否已达上限）")
    private Integer memberCount;
}
