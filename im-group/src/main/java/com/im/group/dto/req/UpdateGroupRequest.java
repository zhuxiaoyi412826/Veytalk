package com.im.group.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改群资料入参。
 *
 * <p>三个字段都是可选的，传 {@code null} 表示不改，传空串表示清空（仅公告与头像允许清空，
 * 群名不能为空）。这个区别由 Service 层判定：DTO 上无法同时表达「不传」与「传空」两种语义，
 * 若一律把空串当成清空，前端只想改公告时就会顺手把群名抹掉。
 */
@Data
@Schema(description = "修改群资料入参")
public class UpdateGroupRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "群名称，为空表示不修改")
    @Size(max = 64, message = "群名称长度不能超过 64")
    private String name;

    @Schema(description = "群头像地址，为空表示不修改")
    @Size(max = 512, message = "头像地址长度不能超过 512")
    private String avatar;

    @Schema(description = "群公告，为空表示不修改")
    @Size(max = 512, message = "群公告长度不能超过 512")
    private String notice;
}
