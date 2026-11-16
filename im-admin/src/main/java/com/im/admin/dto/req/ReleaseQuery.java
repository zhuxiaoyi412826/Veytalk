package com.im.admin.dto.req;

import com.im.common.domain.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 后台客户端版本列表查询入参。
 *
 * <p>排序口径在服务端写死（启用中的排最前、其余按创建时间倒序），不接受外部 order by：
 * 「哪个平台当前启用的是哪一行」是这一屏最常看的信息，让它恒在列表最前面比给一个
 * 排序参数更有用，也少一个能被传歪的入口。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "客户端版本列表查询")
public class ReleaseQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "平台码过滤：windows_setup / windows_portable / android / macos / linux；为空查全部",
            example = "windows_setup")
    private String platform;

    @Schema(description = "状态过滤：0 停用 1 启用；为空查全部", example = "1")
    private Integer status;
}
