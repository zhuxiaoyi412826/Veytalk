package com.im.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.GroupRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 群成员，对应 {@code im_group_member}。
 *
 * <p>退群与被移出都是把 {@code status} 置 0，不做物理删除：唯一键 {@code uk_group_user}
 * 决定了「一个群 + 一个用户」只能有一行，重新入群时复用同一行并把状态改回来，
 * 既保留了入群历史，也避免物理删除后再插入时与其他并发操作抢唯一键。
 *
 * <p>本表没有 {@code deleted} 列，因此继承 {@link AuditEntity} 而不是 {@code BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_group_member")
public class GroupMember extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：在群 */
    public static final int STATUS_IN_GROUP = 1;
    /** 状态：已退群或被移除 */
    public static final int STATUS_LEFT = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    private Long userId;

    /** 群内角色：1 群主 2 管理员 3 普通成员，取值见 {@link GroupRole} */
    private Integer role;

    /** 群内昵称，为空表示沿用账号昵称 */
    private String nicknameInGroup;

    /** 是否被单独禁言：1 是 0 否。属性名不带 is 前缀，避免 getter 推导歧义 */
    @TableField("is_muted")
    private Integer muted;

    /** 禁言到期时间，为空表示无限期禁言 */
    private LocalDateTime muteEndTime;

    /** 最近一次入群时间，重新入群时刷新 */
    private LocalDateTime joinTime;

    private Integer status;

    public boolean isInGroup() {
        return status != null && status == STATUS_IN_GROUP;
    }

    public boolean isOwner() {
        return role != null && role == GroupRole.OWNER.getCode();
    }

    /**
     * 是否具备群管理能力（群主或管理员）。
     */
    public boolean isManager() {
        return role != null && GroupRole.of(role).isManager();
    }

    /**
     * 单人禁言当前是否仍然生效。
     *
     * <p>到期时间是惰性判断的：不额外起定时任务去把过期行的 {@code is_muted} 改回 0，
     * 那样既要扫全表又会与用户手动解除禁言产生竞态，读时比较一次时间代价更低也更准确。
     */
    public boolean isMutedNow() {
        if (muted == null || muted != 1) {
            return false;
        }
        return muteEndTime == null || muteEndTime.isAfter(LocalDateTime.now());
    }
}
