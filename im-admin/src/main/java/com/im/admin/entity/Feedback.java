package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 意见反馈，对应 {@code im_feedback}：一行 = 用户提交的一条反馈与后台对它的处置。
 *
 * <p>表归 im-admin 所有（与公告、版本管理同一先例：不新增 SPI 契约、不动任何 pom 依赖）。
 * 提交人昵称<b>不冗余</b>进本表：后台列表用 {@code AdminUserSpi.listByIds} 批量取——
 * 昵称是会改的，冗余一份就会出现「台账里的名字和现在对不上」；
 * 账号注销后 SPI 查不到，列表按「已注销」显示，反馈行本身仍是有效台账。
 *
 * <p>状态机只有两条边：{@code 0 待处理 → 1 已回复}。{@link #reply} 写入即置 1 并记
 * 回复人/时刻，再次回复覆盖旧文并刷新两列——反馈是客服台账不是合同原件，
 * 不需要「每次回复留一行」的版本化，需要追责时后台操作日志里有。
 *
 * <p>删除是物理删行（后台清理已处置完的台账）；★ 不启 deleted（继承 AuditEntity
 * 而非 BaseEntity），与 im_announcement / im_client_release 同一口径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_feedback")
public class Feedback extends AuditEntity {

    /** 待处理：还没人回复过 */
    public static final int STATUS_PENDING = 0;
    /** 已回复：reply 非空即置此态 */
    public static final int STATUS_REPLIED = 1;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 提交人用户 ID。不建外键：账号注销后反馈行仍是有效台账 */
    private Long userId;

    /** 反馈类型码，取值见 {@link com.im.admin.enums.FeedbackType} */
    private String type;

    /** 反馈正文（≤1000 字），入库前 trim */
    private String content;

    /** 联系方式（选填），空串在写入前归一成 null */
    private String contact;

    /** 状态，取值见本类两个常量 */
    private Integer status;

    /** 后台回复原文；再次回复覆盖旧文 */
    private String reply;

    /** 回复人（后台账号 ID） */
    private Long replyBy;

    /** 最近一次回复时刻 */
    private LocalDateTime replyTime;
}
