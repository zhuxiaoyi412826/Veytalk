package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量邀请成员入参。
 *
 * <p>单次上限 100 人：邀请会触发会话成员写入与系统通知，批量过大会让单个请求持有事务太久，
 * 也会让被邀请方一次性收到上百条推送。
 */
@Data
@Schema(description = "批量邀请成员入参")
public class MemberIdsRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "被邀请的用户 ID 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "成员列表不能为空")
    @Size(max = 100, message = "单次最多邀请 100 人")
    private List<Long> userIds;
}
