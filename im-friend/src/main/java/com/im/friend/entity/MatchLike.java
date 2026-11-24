package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.MatchAction;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 滑动记录（{@code im_match_like}），一行代表「我从某个入口对 TA 做过的最后一个动作」。
 *
 * <p>单向一行：{@code (from -> to)}，双向都 {@code action=1} 才算配对（判定在
 * {@code MatchServiceImpl}，成对结果落到 {@link MatchPair}）。
 *
 * <p>本表<b>没有 {@code deleted} 列</b>，所以继承 {@link AuditEntity} 而不是 {@code BaseEntity}：
 * 滑过一个人是一个事实而不是可回收的对象，撤销喜欢改的是 action，删行会让推荐流失去
 * 「这个人已经被划走」的信号，卡片就会反复回来。
 *
 * <p>{@code updateTime} 是「谁喜欢我」列表的排序依据：改过动作的行会重新冒到最前，
 * 而 DDL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 与 MyBatis-Plus 的填充只会落一个值，不会打架。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_match_like")
public class MatchLike extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作方用户 ID */
    private Long fromUserId;

    /** 被操作用户 ID */
    private Long toUserId;

    /** 动作：1 喜欢 2 跳过，取值见 {@link MatchAction} */
    private Integer action;

    /** 来源场景：1 推荐流 2 附近的人 3 动态 4 访客 5 标签同好，见 {@code MatchSource} */
    private Integer source;

    public boolean isLike() {
        return action != null && action == MatchAction.LIKE.getCode();
    }
}
