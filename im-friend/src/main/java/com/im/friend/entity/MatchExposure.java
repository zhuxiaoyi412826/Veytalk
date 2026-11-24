package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 曝光记录（{@code im_match_exposure}），一行代表「系统把 TA 推给我看过」这件事。
 *
 * <p>与 {@link MatchLike} 分开存：喜欢与跳过是<b>用户表态</b>，曝光是<b>系统行为</b>。
 * 混在一张表里就分不开「推了五次没表态」与「推了五次划走五次」，而这两种人在推荐流里该得到的
 * 待遇相反（前者继续推、后者早就进了 {@code im_match_like} 的排除名单）。
 *
 * <p>本表<b>没有 {@code deleted} 列</b>，所以继承 {@link AuditEntity}：一次曝光是一个发生过的事实，
 * 不是一个可以被用户删除的对象；能删的只有「我对 TA 的表态」。
 *
 * <p>{@code (user_id, candidate_id)} 唯一，所以这张表的大小是「我遇到过的不同人数」，
 * 不随刷新次数增长——同一页刷二十次也只把 {@code expose_count} 加上去。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_match_exposure")
public class MatchExposure extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 隔多少天没被推过，就允许重新进推荐流（口径写在 {@code im_match_exposure} 的建表注释里） */
    public static final int REEXPOSE_DAYS = 7;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被推送一方（看的人） */
    private Long userId;

    /** 被推出去的人（候选） */
    private Long candidateId;

    /** 累计曝光次数 */
    private Integer exposeCount;

    /**
     * 最近一次曝光时间。「超过 {@value #REEXPOSE_DAYS} 天允许重新进池」这条口径按它判断，
     * 所以它必须由服务端写 {@code NOW()}，不能信请求带来的时间。
     */
    private LocalDateTime lastExposeTime;
}
