package com.im.common.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 礼物目录精简信息，跨模块（im-user 持有目录、im-live 送礼）传输使用。
 *
 * <p>刻意不含逻辑删除、时间戳等运维字段：前端礼簿只需要「叫什么、多少分、长什么样、是不是特效」。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "礼物精简信息")
public class GiftBriefDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "礼物 ID")
    private Long giftId;

    @Schema(description = "礼物名称")
    private String name;

    @Schema(description = "图标（表情符号或图片地址）")
    private String icon;

    @Schema(description = "单价（积分）")
    private Integer price;

    @Schema(description = "类型：normal 普通 / effect 特效")
    private String kind;
}
