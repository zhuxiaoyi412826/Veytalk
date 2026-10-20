package com.im.live.dto.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条禁言记录，作为 {@code im:live:mute:{anchorId}} 这个 Hash 的 value 存 JSON。
 *
 * <p>为什么把昵称头像一起塞进 Redis，而不是展示时按 userId 回查用户模块：
 * 面板要一次列出全部被禁的人，逐个回查就是 N 次跨模块调用；而主播要的本来就是
 * <b>当时那个人</b>的名字与头像（改名后再查反而对不上他封禁时看到的脸）。
 * 这份数据允许过期不准（它只是展示用的快照），判定禁言只看 {@link #expireAt}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LiveMutePO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 到期时间戳（毫秒）；{@code 0} 或负数表示永久禁言 */
    private Long expireAt;

    /** 禁言时刻（毫秒），面板按它倒序展示 */
    private Long mutedAt;

    /** 被禁时的昵称快照 */
    private String nickname;

    /** 被禁时的头像快照 */
    private String avatar;
}
