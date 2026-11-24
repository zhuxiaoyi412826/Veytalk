package com.im.friend.dto.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * 我与某个人的亲密度与火花。
 *
 * <p>从没来往过时也会返回一个全 0 的对象而不是 {@code null}：前端要画的是一整块
 * 「火花 / 亲密度 / 共同好友」，返回 null 会让它多一条「这块要不要渲染」的分支，
 * 而 0 分 0 天本身就是准确的事实。
 *
 * <p>{@link #progress} 在服务端算好而不是让前端拿 {@code level} 的分值边界自己除：
 * 档位边界是 {@code IntimacyLevel} 的内部口径，把它下发一份就等于让前端存一份，
 * 将来插一档就会出现两边进度条不一样长。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "亲密度与火花")
public class IntimacyVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "对方用户 ID")
    private Long targetUserId;

    @Schema(description = "亲密度值（只加不减，上限 9999）")
    private Integer score;

    @Schema(description = "档位 code，见 IntimacyLevel：1 初识 2 熟悉 3 亲近 4 默契 5 知心")
    private Integer level;

    @Schema(description = "档位文案")
    private String levelDesc;

    @Schema(description = "升到下一档所需的分值；已是顶档时为 null")
    private Integer nextLevelScore;

    @Schema(description = "当前档位内的进度 0~100；顶档恒为 100")
    private Integer progress;

    @Schema(description = "当前连续聊天天数（火花）；断了会归零重起")
    private Integer streakDays;

    @Schema(description = "历史最高连续天数，火花断了也留着")
    private Integer maxStreak;

    @Schema(description = "火花是否还亮着：今天或昨天聊过为 true")
    private Boolean sparkAlive;

    @Schema(description = "最近一次互聊的自然日；从没聊过为 null")
    private LocalDate lastChatDate;

    @Schema(description = "累计消息条数（双向合计）")
    private Integer msgCount;

    @Schema(description = "累计互动数（动态点赞 / 评论）")
    private Integer interactCount;

    @Schema(description = "共同好友数")
    private Integer commonFriendCount;

    @Schema(description = "共同群数（双方都还在群里的）")
    private Integer commonGroupCount;
}
