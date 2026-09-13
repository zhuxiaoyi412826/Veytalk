package com.im.friend.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改好友分组入参。分组名只做展示归类，不单独建表。
 */
@Data
@Schema(description = "修改好友分组入参")
public class FriendGroupRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分组名", example = "同事", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "分组名不能为空")
    @Size(max = 32, message = "分组名长度不能超过 32")
    private String groupName;
}
