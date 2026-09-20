package com.im.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 会话成员，承载「某个用户在某个会话上的私有视角」。
 *
 * <p>本表没有 {@code deleted} 列，因此继承 {@link AuditEntity} 而非 {@code BaseEntity}；
 * 「删除会话」的语义是本端隐藏（{@link #hidden}），消息与会话主体都保留，
 * 对方再次发消息时会重新出现在列表里。
 *
 * <p>布尔语义的列在数据库中是 TINYINT，这里刻意不把属性命名为 {@code isTop} 之类——
 * Lombok 与 MyBatis-Plus 对 {@code is} 前缀属性的 getter 推导存在歧义，
 * 统一用显式 {@code @TableField} 绑定列名最稳妥。
 *
 * <p>下面的判定方法同理，一律带 {@code Now} 后缀或改用 {@code has} 前缀，绝不能出现
 * {@code isHidden()} 这种与属性同名的写法：{@code @Data} 已经为 {@code Integer hidden}
 * 生成了 {@code getHidden()}，再写一个 {@code isHidden()} 就等于给同一属性挂了第二个
 * 返回类型不同的 getter，违反 JavaBeans 规范，MyBatis 的 Reflector 会直接抛
 * {@code Illegal overloaded getter method with ambiguous type}，本表所有 INSERT 随之失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_conversation_member")
public class ConversationMember extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** 未读消息数 */
    private Integer unreadCount;

    /** 已确认接收位点：离线拉取完成后也会推进，用于消息补齐而非已读判定 */
    private Long lastAckSeq;

    /** 已读位点：仅用户真正打开会话时推进，群聊已读人数由它推算，不再存逐条已读行 */
    private Long lastReadSeq;

    /** 是否置顶：1 是 0 否 */
    @TableField("is_top")
    private Integer top;

    /** 置顶时间，取消置顶时置空 */
    private LocalDateTime topTime;

    /** 是否消息免打扰：1 是 0 否 */
    @TableField("is_muted")
    private Integer muted;

    /** 本端是否隐藏该会话：1 隐藏 0 显示 */
    @TableField("is_deleted")
    private Integer hidden;

    /** 是否有未读的 @ 提醒：1 有 0 无 */
    private Integer atFlag;

    public boolean isTopNow() {
        return top != null && top == 1;
    }

    public boolean isMutedNow() {
        return muted != null && muted == 1;
    }

    public boolean isHiddenNow() {
        return hidden != null && hidden == 1;
    }

    public boolean hasAtFlag() {
        return atFlag != null && atFlag == 1;
    }
}
