package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改好友备注入参。备注传空串或 null 表示清除备注，回退展示对方昵称。
 */
@Data
@Schema(description = "修改好友备注入参")
public class FriendRemarkRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "备注名，留空表示清除备注", example = "老王")
    @Size(max = 32, message = "备注名长度不能超过 32")
    private String remark;
}
