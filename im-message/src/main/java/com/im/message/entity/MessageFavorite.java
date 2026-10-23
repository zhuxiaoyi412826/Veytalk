package com.im.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息收藏标记，对应 {@code im_message_favorite}。
 *
 * <p>一行代表「某个人收藏了某条消息」。与 {@link MessageEssence} 同构，唯一区别是维度：
 * 精华是<b>群维度</b>的公开沉淀（按 conversation_id 聚合、全员可见），收藏是<b>用户维度</b>的
 * 私人笔记（按 user_id 聚合、只有本人看得到），所以唯一键是 {@code uk_user_message}——
 * 同一条消息可以被很多人各自收藏，互不干扰，A 取消收藏不会动到 B 的那一行。
 *
 * <p>为什么建表而不是像禁言/PK 那样放 Redis（那是本项目的默认偏好）：收藏是<b>没有寿命的用户数据</b>。
 * Redis 里现有的兄弟键（限流、心跳、PK 会话、红包领取态）都有明确的过期时间，唯独收藏没有——
 * 放 Redis 要么永不过期（挤占内存，且换实例 / flushdb 就静默全丢，用户只会发现「我的收藏空了」），
 * 要么设 TTL（等于给用户数据判了死刑）。另外「按用户倒序分页」与「整页批量判定哪些已收藏」两种
 * 访问模式都要吃索引，ZSet 撑得住前者、撑不住后者（要 N 次 ZSCORE）。
 *
 * <p>与 {@link MessageEssence}、{@link MessageReaction} 一样刻意不继承基类：本表只有 create_time。
 */
@Data
@TableName("im_message_favorite")
public class MessageFavorite implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 收藏人 ID：收藏只对本人可见，也是本表的聚合维度 */
    private Long userId;

    /** 所属会话 ID（冗余列，供「只看本会话的收藏」过滤，以及清空聊天记录时级联清理） */
    private Long conversationId;

    /** 被收藏的消息 ID */
    private Long messageId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
