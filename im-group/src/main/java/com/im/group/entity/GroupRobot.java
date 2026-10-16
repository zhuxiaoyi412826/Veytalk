package com.im.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 群机器人，对应 {@code im_group_robot}。
 *
 * <p>机器人是「一条带密钥的入站通道」：外部系统 POST 到 {@code /api/robot/webhook/{webhookToken}}，
 * 服务端据 token 反查机器人 → 群 → 会话，再以 {@code MsgType.ROBOT} 落一条内部消息。机器人本身
 * 不是用户账号，其名称与头像在发送时快照进消息 extra（见 {@code RobotContentHandler}）。
 *
 * <p>{@code webhookToken} 是这条通道的唯一凭证（能力 URL 模式：拿到即可发），因此删除机器人走
 * 逻辑删除（继承 {@code BaseEntity}）：软删后 token 不再命中，历史消息里的机器人身份快照不受影响。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_group_robot")
public class GroupRobot extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 1;
    /** 状态：停用（webhook 到达时直接拒绝） */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    /** 机器人展示名，发送时快照进消息 extra */
    private String name;

    /** 机器人头像 URL，可为空 */
    private String avatar;

    /** 用途说明，仅管理面板展示 */
    private String description;

    /** Webhook 密钥，全局唯一；出现在回调 URL 里，等同访问凭证 */
    private String webhookToken;

    /** 创建者用户 ID（群主或管理员），用于审计 */
    private Long creatorId;

    /** 状态，取值见本类 {@code STATUS_*} 常量 */
    private Integer status;

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }
}
