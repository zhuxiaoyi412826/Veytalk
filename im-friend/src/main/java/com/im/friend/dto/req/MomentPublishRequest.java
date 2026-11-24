package com.im.friend.dto.req;

import com.im.friend.entity.Moment;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 发布一条动态。
 *
 * <p>{@link #content} 与 {@link #media} 至少要有一样：<b>空正文 + 零附件</b>的动态在 Feed 上
 * 是一格纯白的卡片，既没有信息也没有可点的入口，而它照样占一次曝光与一次「三天可见」的名额。
 * 这条规则放在服务层而不是 {@code @NotBlank}，因为「二选一」用注解表达不出来
 * （两个字段各自加 {@code @NotBlank} 就变成「都得填」）。
 *
 * <p>{@link #visibleUserIds} 只在 {@code visibility = 3} 时被读：其余三档的判据在关系侧，
 * 传了也一律忽略——按它写白名单会让「仅好友」的动态多出一份没人读的行，
 * 而将来有人把判据改成读白名单时，那批行就会变成越权的入口。
 */
@Data
@Schema(description = "发布动态请求")
public class MomentPublishRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 白名单一次最多挑多少人：够用了，而再大就该改「仅好友」而不是逐个点名 */
    public static final int WHITELIST_MAX = 200;

    /** 附件条数上限：9 图或 1 视频，取两者的较大值当校验上界，具体组合由服务层判 */
    public static final int MEDIA_MAX = Moment.MAX_IMAGES;

    @Schema(description = "正文，可空（纯图动态）；与附件至少要有一样", example = "周末去了趟海边")
    @Size(max = Moment.CONTENT_MAX, message = "正文最多 " + Moment.CONTENT_MAX + " 字")
    private String content;

    @Schema(description = "可见范围：1 公开（好友与配对对象可见） 2 仅好友 3 部分人可见 4 仅自己", example = "1")
    @NotNull(message = "请选择可见范围")
    private Integer visibility;

    @Schema(description = "附件列表，最多 9 张图或 1 个视频，顺序即九宫格顺序")
    @Valid
    @Size(max = MEDIA_MAX, message = "一条动态最多 " + MEDIA_MAX + " 个附件")
    private List<MomentMediaItem> media;

    @Schema(description = "「部分人可见」时允许看到这条动态的用户 ID；其余档位传了也忽略")
    @Size(max = WHITELIST_MAX, message = "最多挑 " + WHITELIST_MAX + " 个人")
    private List<Long> visibleUserIds;
}
