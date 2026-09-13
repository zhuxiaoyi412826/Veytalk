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

    private Integer status;

    public boolean isMuteAllOn() {
        return muteAll != null && muteAll == 1;
    }

    public boolean isDismissed() {
        return status != null && status == STATUS_DISMISSED;
    }

    public boolean isNormal() {
        return status != null && status == STATUS_NORMAL;
    }
}
