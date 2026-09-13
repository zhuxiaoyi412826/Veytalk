package com.im.friend.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 好友申请列表查询入参。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "好友申请列表查询参数")
public class FriendRequestQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "按状态过滤：0 待处理 1 已同意 2 已拒绝 3 已过期；不传表示全部", example = "0")
    @Min(value = 0, message = "状态取值范围为 0-3")
    @Max(value = 3, message = "状态取值范围为 0-3")
    private Integer status;
}
