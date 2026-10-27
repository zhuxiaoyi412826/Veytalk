package com.im.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 全服公告，对应 {@code im_announcement}。
 *
 * <p>它是后台第一次「主动对所有在线连接喊话」：发布那一刻经 {@code PushSpi.broadcast()}
 * 推一帧 {@code notify}（{@code action=announce}）给全部在线连接，落到用户端是一条站内通知。
 * 报文不新增 {@code WsMessageType}，理由与举报回执同一条——{@code notify} 这个类型下
 * 早已是「按 data.action 分流」的形状（connected / report-result / admin-warn / 群事件），
 * 为一个新动作再开一种帧类型，用户端就要多认一个入口，而它本来已经有四个分支了。
 *
 * <p><b>离线用户靠这张表补，不靠推送</b>：推送是即时的、错过就没有，所以本表必须持久化，
 * 用户端启动时拉一次「当前生效的公告」再按 ID 去重。没有 {@code im_announcement_read}
 * 这种已读表（见类尾的已知边界），去重记在客户端本地。
 *
 * <p>状态机只有三条边：草稿 → 已发布 → 已撤回。<b>已发布不能编辑</b>是刻意的——
 * 改一条已经广播出去的正文，等于让在线的人看到的是旧文本、之后登录的人看到的是新文本，
 * 而这两种人都会以为自己是「看到公告的那个人」。要改就撤回旧的再发一条新的。
 *
 * <p>物理删除只允许对草稿；已发布与已撤回的行留下，它们是「什么时候说过什么话」的台账。
 *
 * <p>已知边界（本期不做，写在文档里而不是藏在代码里）：
 * <ul>
 *   <li>没有已读回执表：同一台设备记住看过哪些 ID，换设备会重看一遍；</li>
 *   <li>撤回收不回已经弹出去的那条通知，只影响之后的拉取；</li>
 *   <li>广播不回报送达数（{@code PushSpi.broadcast} 返回 void），所以后台只显示「已广播」
 *       而不显示「N 人收到」——拿在线用户数凑这个数字会是假数。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_announcement")
public class Announcement extends AuditEntity {

    /** 草稿：只在后台可见，不推送、也不返回给用户端 */
    public static final int STATUS_DRAFT = 0;
    /** 已发布：已广播，且在 {@code expire_time} 之前会返回给用户端的补拉 */
    public static final int STATUS_PUBLISHED = 1;
    /** 已撤回：不再返回给用户端；已经推出去的通知收不回来 */
    public static final int STATUS_REVOKED = 2;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 标题，通知的标题位直接用它的原文，不让前端拿正文截断去猜 */
    private String title;

    /** 正文，写入前按敏感词遮罩（公告也要过自己那套词库，否则后台可以绕过它） */
    private String content;

    /**
     * 通知样式：info / success / warning / error，与举报回执的 {@code tone} 同一口径。
     * 收在服务端而不是让前端猜，理由与三期一致：改一句中文文案不该把通知颜色改掉。
     */
    private String tone;

    /** 状态，取值见本类三个常量 */
    private Integer status;

    /** 发布时刻；草稿为空。用户端按它倒序取「最新几条」 */
    private LocalDateTime publishTime;

    /**
     * 失效时刻，可空表示不失效。
     *
     * <p>只影响「用户端补拉还能不能拉到」，<b>不会自动把已发布的公告改成已撤回</b>：
     * 到期与状态是两件事，库里那行仍然是已发布的历史事实。
     */
    private LocalDateTime expireTime;

    /** 创建人（后台账号 ID） */
    private Long createBy;

    /** 最后修改人（后台账号 ID）：发布与撤回都算修改，所以这一列总是比 createBy 新 */
    private Long updateBy;
}
