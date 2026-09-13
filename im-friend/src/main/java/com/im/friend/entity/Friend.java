package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.FriendStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 好友关系，双向各存一行：{@code (userId, friendId)} 与 {@code (friendId, userId)}。
 *
 * <p>每行独立维护「我对 TA 的备注 / 分组 / 拉黑状态」，因此拉黑是<b>单向语义</b>：
 * {@code status=2} 只表示我把 friendId 拉黑，不影响对方那一行的状态。
 *
 * <p>本表没有 {@code deleted} 列，删除好友即物理删除两行，让唯一键 {@code uk_user_friend}
 * 立刻释放，同一对用户才能重新申请加好友。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_friend")
public class Friend extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 默认好友分组名，与 DDL 中的列默认值保持一致 */
    public static final String DEFAULT_GROUP = "默认分组";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关系持有者 */
    private Long userId;

    /** 好友的用户 ID */
    private Long friendId;

    /** 我对好友的备注名，为空表示未设置 */
    private String remark;

    /** 好友分组名 */
    private String groupName;

    /** 状态：1 正常 2 已拉黑（我拉黑了 friendId） */
    private Integer status;

    public boolean isBlocked() {
        return status != null && status == FriendStatus.BLOCKED.getCode();
    }

    public boolean isNormal() {
        return status != null && status == FriendStatus.NORMAL.getCode();
    }
}
