package com.im.message.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消息收藏入参。
 *
 * <p>{@code favorite=true} 把该消息收进本人的收藏（幂等），{@code false} 取消收藏。收藏是
 * 用户维度的私人行为：单聊群聊都能收，只有本人看得到，不需要任何管理权限。
 */
@Data
@Schema(description = "消息收藏入参")
public class FavoriteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "true 收藏，false 取消收藏")
    private Boolean favorite;
}
