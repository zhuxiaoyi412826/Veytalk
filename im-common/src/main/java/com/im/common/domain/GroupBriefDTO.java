package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 群组精简信息，跨模块传输使用。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群组精简信息")
public class GroupBriefDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群 ID")
    private Long groupId;

    @Schema(description = "群名称")
    private String name;

    @Schema(description = "群头像")
    private String avatar;

    @Schema(description = "群公告")
    private String notice;

    @Schema(description = "群主 ID")
    private Long ownerId;

    @Schema(description = "成员数")
    private Integer memberCount;

    @Schema(description = "我在群内的角色：1 群主 2 管理员 3 成员")
    private Integer myRole;

    @Schema(description = "我在群内的昵称")
    private String myNickname;
}
