package com.im.friend.dto.vo;

import com.im.common.domain.DatingCardDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一次滑动动作的结果。
 *
 * <p>除了「成没成对」还把会话 ID 一起交出来：成对的那一刻是交友产品里最值得立刻跳进聊天的
 * 一个瞬间，让前端为此再发一次「查我的配对」请求，会把最佳时机花在往返上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "滑动动作结果")
public class MatchActionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本次落库的动作：1 喜欢 2 跳过")
    private Integer action;

    @Schema(description = "动作文案")
    private String actionDesc;

    @Schema(description = "是否配对成功（双向喜欢）")
    private Boolean matched;

    @Schema(description = "配对 ID，未成对时为 null")
    private Long pairId;

    @Schema(description = "配对后的私聊会话 ID，未成对时为 null；前端可直接跳进该会话")
    private Long conversationId;

    @Schema(description = "对方的交友卡片，未成对时为 null；成对弹层上要显示的是这个人，不是另一个人的资料")
    private DatingCardDTO peer;

    @Schema(description = "给用户看的一句话，直接可作 toast 文案")
    private String message;
}
