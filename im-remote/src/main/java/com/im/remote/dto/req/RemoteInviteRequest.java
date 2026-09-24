package com.im.remote.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起远程邀请入参。
 *
 * <p>permission 只能是 readonly / operate 两档，且这里表达的只是「请求」——
 * 被控端授权时可以降档（operate → readonly），升档在任何一层都不被允许。
 */
@Data
@Schema(description = "发起远程邀请请求")
public class RemoteInviteRequest {

    @Schema(description = "目标设备标识（Agent 安装时生成）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "deviceId 不能为空")
    @Size(max = 64, message = "deviceId 过长")
    private String deviceId;

    @Schema(description = "请求权限：readonly 只读 / operate 可操作，默认 operate",
            example = "operate", allowableValues = {"readonly", "operate"})
    private String permission;
}
