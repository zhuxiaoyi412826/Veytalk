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
 * 「附近的人」列表项。
 *
 * <p><b>刻意没有精确距离</b>：库里能算出多少米，但对外只有 {@code card.distanceText}
 * 那一句模糊文案（500 m 内 / 1 km 内 / 取整公里 / 100 km 以上）。按距离排序用的是服务端内存里的
 * 精确值，排序会泄露「远近次序」却泄露不出可用来跟踪的坐标——这正是距离模糊化要保住的边界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "附近的人")
public class NearbyPeerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "对方用户 ID")
    private Long peerUserId;

    @Schema(description = "对方的交友卡片（distanceText 就是这一行显示的模糊距离）")
    private DatingCardDTO card;

    @Schema(description = "我喜欢过 TA（列表上按钮显示「已喜欢」而不是「喜欢」）")
    private Boolean liked;

    @Schema(description = "已配对，可以直接聊天")
    private Boolean paired;

    @Schema(description = "已配对时的会话 ID，未配对为 null")
    private Long conversationId;
}
