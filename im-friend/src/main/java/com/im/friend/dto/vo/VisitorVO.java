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
 * 「谁看过我」列表项（{@code im_profile_visit} 中 {@code visited_id=我} 的行，按人聚合）。
 *
 * <p>与 {@link MatchLikeVO} 同样是「嵌套一张卡片」而不是把昵称头像摊平：卡片是 im-user
 * 收口过隐私的唯一产物，摊平一次就等于在那边复制一份收口逻辑。
 *
 * <p>{@code visitDays} / {@code visitTimes} 都是<b>回看窗口内</b>（
 * {@code ProfileVisit.LIST_WINDOW_DAYS} 天）的累计值，不是全历史：列表讲的是「最近谁对我有兴趣」，
 * 而三年前的访问既解释不了今天的这条，也会让老面孔永远霸在头部。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "看过我的人")
public class VisitorVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "来访者用户 ID")
    private Long visitorId;

    @Schema(description = "对方的交友卡片（已按我的视角收口年龄、距离与在线位）")
    private DatingCardDTO card;

    @Schema(description = "最近一次来看我的时间，列表按它倒序")
    private LocalDateTime lastTime;

    @Schema(description = "窗口内来过几天（一人一天一行，这就是行数）")
    private Integer visitDays;

    @Schema(description = "窗口内一共看了多少次（各行 times 之和）")
    private Integer visitTimes;

    @Schema(description = "最近一次是从哪个入口看的：1 资料卡 2 照片墙 3 动态列表 4 匹配卡片")
    private Integer viewType;

    @Schema(description = "入口文案")
    private String viewDesc;

    @Schema(description = "我已经喜欢过 TA")
    private Boolean liked;

    @Schema(description = "是否已配对")
    private Boolean paired;

    @Schema(description = "已配对时的会话 ID，未配对为 null")
    private Long conversationId;
}
