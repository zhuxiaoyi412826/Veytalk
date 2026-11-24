package com.im.friend.dto.vo;

import com.im.common.domain.DatingCardDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 我的配对列表项。
 *
 * <p>{@code conversationId} 是这一页最有用的字段：配对的意义就是能说话，列表上「去聊天」
 * 直接拿它跳转会话，比让前端再算一次 {@code s:min:max} 会话键可靠。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交友配对")
public class MatchPairVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "配对 ID，解除配对时提交它")
    private Long pairId;

    @Schema(description = "对方用户 ID")
    private Long peerUserId;

    @Schema(description = "对方的交友卡片")
    private DatingCardDTO card;

    @Schema(description = "私聊会话 ID")
    private Long conversationId;

    @Schema(description = "状态：1 配对中 2 已解除")
    private Integer status;

    @Schema(description = "配对时间")
    private LocalDateTime pairTime;

    @Schema(description = "在哪成对的：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好")
    private Integer likeSource;

    @Schema(description = "来源场景文案")
    private String likeSourceDesc;

    @Schema(description = "配对后累计消息数（本批由消息链路补写，当前可能恒为 0）")
    private Integer msgCount;

    @Schema(description = "配对后最近一条消息时间，一句没说时为 null")
    private LocalDateTime lastMsgTime;
}
