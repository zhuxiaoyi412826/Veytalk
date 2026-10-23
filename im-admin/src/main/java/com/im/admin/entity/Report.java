package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.im.common.domain.ReportTargetDTO;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 举报工单，对应 {@code im_report}。
 *
 * <p>本表是<b>管理后台唯一的业务表</b>（im-admin 不持有其它表），也是项目里「项目默认偏好（零 DDL）
 * 让位给数据寿命」的又一个例子，与消息收藏同构但更强：举报是<b>用来追责的持久记录</b>，
 * Redis 里放不住「三天后回来看结果」和「这个主播被投诉过几次」这两种访问，
 * 而两者都要索引。
 *
 * <p>继承 {@link AuditEntity} 而不继承 {@code BaseEntity}：本表没有 {@code deleted} 列，
 * 处置即改 {@code status}，历史行永不删除——一张被软删的举报单在合规视角上是消失的证据。
 *
 * <p>{@code autoResultMap = true} 是 {@link #evidence} JSON 列能被 TypeHandler 反序列化的前提，
 * 缺了它查询回来只会得到原始字符串（与 {@code im_message.extra} 同一个坑）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "im_report", autoResultMap = true)
public class Report extends AuditEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 举报人用户 ID，一定是登录者本人，不接受前端传入 */
    private Long reporterId;

    /** 被举报对象类型：user / message / room / group，取值见 {@code ReportTargetType} */
    private String targetType;

    /** 被举报对象 ID，按 {@link #targetType} 解释，单独看没有意义 */
    private Long targetId;

    /**
     * 被举报人 ID：提交时由各模块的 SPI 从对象反查出来（消息→发送者、房间→主播、群→群主）。
     * 单独存一列而不是每次去查对象，是因为<b>对象事后会被删</b>，而「同一个人被举报了几次」
     * 这个聚合要能一直算得出来。反查不到时为空（例如匿名系统消息），处置时只能走「不处理 / 驳回」。
     */
    private Long reportedUserId;

    /** 举报理由码，取值见 {@code ReportReason}；自由文本不允许当分类用 */
    private String reasonCode;

    /** 补充描述，已按敏感词遮罩与长度截断；部分理由必填 */
    private String reasonText;

    /** 提交那一刻抓的证据快照，以 JSON 列存储；对象被删之后工单仍然可判全靠它 */
    @TableField(value = "evidence", typeHandler = Jackson3TypeHandler.class)
    private ReportTargetDTO evidence;

    /** 状态：0 待处理 1 已受理 2 已驳回 3 已处置，取值见 {@code ReportStatus} */
    private Integer status;

    /** 处置动作码，取值见 {@code ReportDisposition}；与 {@link #status} 为 3 时同时写入 */
    private String disposition;

    /** 处置说明，会回显给举报人 */
    private String handleRemark;

    /**
     * 处理人（管理员）用户 ID：受理那一刻就写，标明「这单有人认领了」。
     */
    private Long handlerId;

    /**
     * 进入<b>终态</b>（已驳回 / 已处置）的时刻。受理不写这一列，因此
     * {@code status = 1} 且本列为空就是「已认领但还没结论」，两者不必再加一列就能区分。
     */
    private LocalDateTime handleTime;
}
