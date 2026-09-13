package com.im.user.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户搜索入参，用于添加好友前的账号 / 昵称 / 手机号检索。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户搜索请求")
public class UserSearchQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否精确匹配：true 时账号与手机号走全等，昵称仍为模糊", example = "false")
    private Boolean exact = Boolean.FALSE;

    public boolean isExact() {
        return Boolean.TRUE.equals(exact);
    }
}
