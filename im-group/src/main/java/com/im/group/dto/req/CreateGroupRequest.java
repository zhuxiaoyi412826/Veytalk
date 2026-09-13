package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 创建群组入参。
 *
 * <p>创建者自动成为群主，不需要也不能在 {@code memberIds} 里指定自己；
 * 初始成员可以为空，建群之后再逐个邀请，符合「先拉个空群再往里加人」的常见用法。
 */
@Data
@Schema(description = "创建群组入参")
public class CreateGroupRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群名称", example = "项目讨论组", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "群名称不能为空")
    @Size(max = 64, message = "群名称长度不能超过 64")
    private String name;

    @Schema(description = "群头像地址，为空时使用默认头像")
    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String avatar;

    @Schema(description = "群公告")
    @Size(max = 512, message = "群公告长度不能超过 512")
    private String notice;

    @Schema(description = "初始成员 ID 列表，不含创建者自己，单次最多 100 人")
    @Size(max = 100, message = "单次最多邀请 100 人")
    private List<Long> memberIds;

    @Schema(description = "成员数上限，默认 200", example = "200")
    @Min(value = 2, message = "成员上限不能小于 2")
    @Max(value = 1000, message = "成员上限不能大于 1000")
    private Integer maxMember;
}
