package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改我在群内的昵称入参。
 *
 * <p>这是唯一由普通成员自助修改的群字段，因此不校验权限，只要求调用者本人在群内。
 * 留空表示清除群昵称，回退展示账号昵称。
 */
@Data
@Schema(description = "修改群内昵称入参")
public class GroupNicknameRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群内昵称，留空表示清除，回退展示账号昵称")
    @Size(max = 32, message = "群内昵称长度不能超过 32")
    private String nicknameInGroup;
}
