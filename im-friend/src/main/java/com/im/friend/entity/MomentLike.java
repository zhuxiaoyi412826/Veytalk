package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 一次点赞（{@code im_moment_like}），一行代表「这个人赞过这条动态」。
 *
 * <p><b>没有 {@code deleted} 列</b>，所以继承 {@link AuditEntity}：取消点赞是<b>物理删行</b>，
 * 与 {@code im_friend} 同一口径。留着行只会让 {@code uk_moment_user} 挡住「取消后再点一次」，
 * 而为了不撞唯一键再加一列 liked 标记，就等于把一张点赞表变成一张需要读两遍才知道结果的表。
 *
 * <p>因此这里<b>没有</b>「谁在什么时候取消过」的历史：交友场景里这个信号没有消费者，
 * 真要留痕得另开一张流水表，而它的写入量是点赞量的两倍。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_moment_like")
public class MomentLike extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long momentId;

    /** 点赞用户 ID */
    private Long userId;
}
