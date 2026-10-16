package com.im.group.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 群机器人视图对象（仅群主与管理员可见）。
 *
 * <p>{@code webhookUrl} 由服务端按前端传入的站点根地址拼出，token 即访问凭证：
 * 外部系统只需向该 URL POST {@code {"content": "..."}} 即可在本群发消息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "群机器人信息")
public class GroupRobotVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "机器人 ID")
    private Long robotId;

    @Schema(description = "所属群 ID")
    private Long groupId;

    @Schema(description = "机器人名称")
    private String name;

    @Schema(description = "机器人头像 URL")
    private String avatar;

    @Schema(description = "用途说明")
    private String description;

    @Schema(description = "Webhook 密钥")
    private String webhookToken;

    @Schema(description = "完整 Webhook 回调地址（外部系统 POST 此地址发消息）")
    private String webhookUrl;

    @Schema(description = "状态：1 启用 0 停用")
    private Integer status;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "创建者用户 ID")
    private Long creatorId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
