package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 配对关系（{@code im_match_pair}）：双向喜欢成一行，带私聊会话 ID。
 *
 * <p><b>{@code userAId} 恒小于 {@code userBId}</b>，由服务层写入前排序保证。约定这一条是因为
 * 一对人在表里只能有一行，{@code uk_pair} 才有意义；否则 {@code (1,2)} 与 {@code (2,1)} 各存一行，
 * 每次查询都要写两个方向，而漏写的那个方向会表现成「明明配对了却查不到」。
 *
 * <p>与 {@code im_friend} 完全独立：配对不自动加好友（用户选定口径），所以本表的一行解除后
 * 置 {@link #STATUS_UNPAIRED} 而不是删行——会话与聊天记录都不归配对管，删聊天记录的语义在
 * {@code im_conversation} 那一侧。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_match_pair")
public class MatchPair extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 配对中 */
    public static final int STATUS_ACTIVE = 1;
    /** 已解除：行保留，重新喜欢时复用本行置回 {@link #STATUS_ACTIVE} */
    public static final int STATUS_UNPAIRED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 较小的一方用户 ID */
    private Long userAId;

    /** 较大的一方用户 ID */
    private Long userBId;

    /** 配对时由 {@code ConversationSpi} 建好的私聊会话 ID */
    private Long conversationId;

    /** 状态：1 配对中 2 已解除 */
    private Integer status;

    /** 在哪个入口成对的：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好 */
    private Integer likeSource;

    /** 配对后累计消息数，供「三天无互聊降权」与僵尸配对提示用（本批不写，由消息链路补） */
    private Integer msgCount;

    private LocalDateTime lastMsgTime;

    /** 这一行是否含我。 */
    public boolean contains(Long userId) {
        if (userId == null) {
            return false;
        }
        return userId.equals(userAId) || userId.equals(userBId);
    }

    /** 相对于 {@code viewer} 的另一方 ID。调用前须先 {@link #contains} 校验，否则返回 null。 */
    public Long peerId(Long viewer) {
        if (viewer == null) {
            return null;
        }
        return viewer.equals(userAId) ? userBId : userAId;
    }

    public boolean isActive() {
        return status != null && status == STATUS_ACTIVE;
    }
}
