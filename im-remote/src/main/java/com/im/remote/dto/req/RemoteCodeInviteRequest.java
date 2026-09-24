package com.im.remote.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 凭识别码发起远程邀请入参（ToDesk 式跨账号路径）。
 *
 * <p>code 格式在入参层就锁死为 6-12 位大写字母数字（服务端会先转大写），
 * 与 Agent 侧生成规则、握手 auth 帧校验三处一致；正则前置可以挡掉
 * 明显不合法的探测请求，不让它们走到注册表遍历。
 */
@Data
@Schema(description = "凭识别码发起远程邀请请求")
public class RemoteCodeInviteRequest {

    @Schema(description = "被控端 Agent 显示的识别码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "A1B2C3")
    @NotBlank(message = "识别码不能为空")
    @Pattern(regexp = "[A-Za-z0-9]{6,12}", message = "识别码为 6-12 位字母或数字")
    private String code;

    @Schema(description = "请求权限：readonly 只读 / operate 可操作，默认 operate",
            example = "operate", allowableValues = {"readonly", "operate"})
    private String permission;
}
