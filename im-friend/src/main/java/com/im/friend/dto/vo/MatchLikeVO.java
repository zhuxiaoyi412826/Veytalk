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
 * 「谁喜欢我」列表项（{@code im_match_like} 中 {@code to_user_id=我} 且 {@code action=1} 的行）。
 *
 * <p>这一页是最容易被当成探测工具用的页面，所以只回「TA 喜欢我」这一件事，
 * 不回「我有没有喜欢过 TA 之外」的任何对方侧信息；{@code likedBack} 与 {@code paired}
 * 都是<b>我这一侧</b>的状态，前端据此决定按钮文案是「回个喜欢」还是「去聊天」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "喜欢我的人")
public class MatchLikeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "滑动记录 ID")
    private Long likeId;

    @Schema(description = "对方用户 ID")
    private Long peerUserId;

    @Schema(description = "对方的交友卡片（已按我的视角收口年龄、距离与在线位）")
    private DatingCardDTO card;

    @Schema(description = "TA 最近一次把我划成「喜欢」的时间，列表按它倒序")
    private LocalDateTime likeTime;

    @Schema(description = "TA 在哪个入口喜欢我：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好")
    private Integer source;

    @Schema(description = "来源场景文案")
    private String sourceDesc;

    @Schema(description = "我已经回过喜欢（双向喜欢即已成对）")
    private Boolean likedBack;

    @Schema(description = "是否已配对")
    private Boolean paired;

    @Schema(description = "已配对时的会话 ID，未配对为 null")
    private Long conversationId;
}
