package com.im.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 同意留痕（一条 = 某个用户在某个时刻对某个具体版本的一次表态），对应 {@code im_user_agreement}。
 *
 * <p>这张表是整个合规链路里唯一拿得出证据的东西。挂一份公示文本在官网上不构成「取得单独同意」，
 * 监管与原告的律师要看的是一行行能对上的记录：<b>谁、什么时候、从哪个入口、对着哪个版本、
 * 点了同意还是拒绝、从哪个 IP 与设备</b>。少任何一列，举证时都要现编说辞。
 *
 * <p>同样不继承 {@code BaseEntity}：留痕是流水，删一条就等于销毁证据，法定期限内没有任何角色
 * 有权删（后台不提供删除端点，运营与管理员都不行）。它的清理只跟日志留存策略走，
 * 不跟业务走。
 *
 * <p>{@code code} 与 {@code version} 是从 {@link Agreement} 抄下来的<b>冗余快照</b>：
 * 留痕要能整表导出成一份自足的材料，不该依赖主表当时长成什么样。
 *
 * <p>没有唯一键：同一版同意两次（换设备再登一次）技术上会重复插入，但服务层每次先查
 * 「这一版是否已经同意过」，同意过就不再补写——不加唯一键是为了让「拒绝 → 后来改主意同意」
 * 这条路留得下来，那正是可选服务条款的正常走向，唯一键会把它挡成主键冲突。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_user_agreement")
public class UserAgreement extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作：同意 */
    public static final int ACTION_ACCEPT = 1;
    /** 动作：拒绝（可选条款才有拒绝；必签条款在前端就是「不同意则不注册 / 不继续」） */
    public static final int ACTION_REJECT = 0;

    /** 场景：注册弹窗勾选，账号是这一次点击的产物 */
    public static final String SOURCE_REGISTER = "register";
    /** 场景：短信 / 邮箱验证码登录自动建号 */
    public static final String SOURCE_LOGIN = "login";
    /** 场景：登录后的强制再同意弹窗（文本改版，或账号建出来时还没走到同意这一步） */
    public static final String SOURCE_DIALOG = "dialog";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 表态的用户 ID */
    private Long userId;

    /** 指向 {@link Agreement} 的具体版本行：点进去能看到他当时同意的那份原文 */
    private Long agreementId;

    /** 协议标识快照 */
    private String code;

    /** 版本号快照 */
    private Integer version;

    /** 动作，见 {@code ACTION_*} */
    private Integer action;

    /** 场景，见 {@code SOURCE_*} */
    private String source;

    /** 来源 IP，与登录日志同一口径（含反代头） */
    private String ip;

    /** User-Agent，写入前截到列宽；判断「是不是本人设备」的唯一线索 */
    private String userAgent;

    public boolean accepted() {
        return action != null && action == ACTION_ACCEPT;
    }
}
