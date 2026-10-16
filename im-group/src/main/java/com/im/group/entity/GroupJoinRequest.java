package com.im.group.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 入群申请，对应 {@code im_group_join_request}。
 *
 * <p>仅当群开启入群审批（{@code im_group.join_approval = 1}）时，非成员通过邀请链接 / 二维码申请入群才会写入本表。
 *
 * <p>同一 {@code (group_id, user_id)} 只保留一行（唯一键 {@code uk_group_user}）：
 * 拒绝后再次提交会复用同一行并把状态拉回「待审批」，不堆积历史记录。这与
 * {@code im_group_member} 的 {@code uk_group_user} 语义一致——反正都是「一个群 + 一个人」的一次事件序列。
 *
 * <p>本表没有 {@code deleted} 列，因此继承 {@link AuditEntity} 而不是 {@code BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_group_join_request")
public class GroupJoinRequest extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：待审批 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已通过（管理员同意，申请人已入群） */
    public static final int STATUS_APPROVED = 1;
    /** 状态：已拒绝 */
    public static final int STATUS_REJECTED = 2;

    /** 来源：邀请链接（点击网页链接进入申请） */
    public static final String SOURCE_LINK = "link";
    /** 来源：二维码（客户端扫描后进入申请） */
    public static final String SOURCE_QRCODE = "qrcode";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long groupId;

    private Long userId;

    /** 状态，取值见本类 {@code STATUS_*} 常量 */
    private Integer status;

    /** 申请留言，可为空 */
    private String message;

    /** 来源，取值见本类 {@code SOURCE_*} 常量 */
    private String source;

    /** 处理人用户 ID（群主或管理员），未处理时为空 */
    private Long handleUserId;

    /** 处理时间，未处理时为空 */
    private LocalDateTime handleTime;

    public boolean isPending() {
        return status != null && status == STATUS_PENDING;
    }
}
