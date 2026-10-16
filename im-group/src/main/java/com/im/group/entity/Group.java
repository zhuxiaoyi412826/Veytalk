package com.im.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 群组，对应 {@code im_group}。
 *
 * <p>解散群不是物理删除：{@code status} 置 0 后群名、公告、成员历史都还在，
 * 历史消息里的「来自 xx 群」才有据可查；同时 {@code deleted} 逻辑删除列由基类提供，
 * 两者语义不同——前者是业务状态（用户主动解散），后者是数据治理层面的软删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_group")
public class Group extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：正常 */
    public static final int STATUS_NORMAL = 1;
    /** 状态：已解散 */
    public static final int STATUS_DISMISSED = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String avatar;

    /** 群公告，群主与管理员可改 */
    private String notice;

    /** 群主用户 ID，转让群主时同步更新 */
    private Long ownerId;

    /** 成员数上限，超出后拒绝入群 */
    private Integer maxMember;

    /** 全员禁言：1 开启 0 关闭。属性名不带 is 前缀，避免 Lombok 与 MyBatis-Plus 的 getter 推导歧义 */
    @TableField("mute_all")
    private Integer muteAll;

    /**
     * 群邀请码，邀请链接与二维码都基于它。
     *
     * <p>懒生成：默认 {@code null}，首次管理员调用 {@code getInviteCode} 时写入一个随机短码（不依赖建群时多写一笔）；
     * 全局唯一 {@code uk_invite_code}。不采用定时失效，与项目内“退群后可重新入”一样不做过期。
     */
    private String inviteCode;

    /**
     * 入群是否需要审批：1 需要、管理员确认后才正式入；0 不需要，任何拿到邀请码/扫码的人直接入群。
     *
     * <p>属性名不带 is 前缀，同 {@code muteAll} 一样避免 getter 推导歧义。
     */
    @TableField("join_approval")
    private Integer joinApproval;

    private Integer status;

    public boolean isMuteAllOn() {
        return muteAll != null && muteAll == 1;
    }

    /**
     * 入群时是否需要管理员审批。
     */
    public boolean isJoinApprovalOn() {
        return joinApproval != null && joinApproval == 1;
    }

    public boolean isDismissed() {
        return status != null && status == STATUS_DISMISSED;
    }

    public boolean isNormal() {
        return status != null && status == STATUS_NORMAL;
    }
}
