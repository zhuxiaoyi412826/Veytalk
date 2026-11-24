package com.im.friend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.im.common.entity.AuditEntity;
import com.im.common.enums.IntimacyLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDate;

/**
 * 一对人的亲密度与火花，对应表 {@code im_intimacy}（一行一对人，{@code uk_pair} 唯一）。
 *
 * <p>继承 {@link AuditEntity}：这张表没有 {@code deleted} 列。亲密度是「这两个人来往过多久」的
 * 累计事实，删好友、解除配对都不该把它抹掉——重新加回来时还认得彼此，比从 0 分重新开始合理。
 *
 * <p>{@code userAId} 恒为较小的一方，与 {@code im_match_pair} 同一个约定：
 * 一行一对人只有在两个方向都归一成同一行时才成立，否则 A→B 与 B→A 会各存一行，
 * 火花天数变成「谁先写的那一行说了算」。
 *
 * <p>★ 本类<b>不定义档位的分值边界</b>，那是 {@link IntimacyLevel} 的事；
 * 这里只放「一次动作加多少分」与「分值上限」，因为它们是写入侧的口径。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("im_intimacy")
public class Intimacy extends AuditEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 一条单聊消息加的分：火花已经按天记着「聊没聊」，分值只用来区分「聊得多」与「聊得少」 */
    public static final int SCORE_PER_MESSAGE = 1;
    /** 给对方的动态点赞加的分 */
    public static final int SCORE_PER_MOMENT_LIKE = 2;
    /** 评论对方的动态加的分：写了一句话，比一个赞重 */
    public static final int SCORE_PER_MOMENT_COMMENT = 3;

    /**
     * 分值上限。
     *
     * <p>夹上限不是因为怕溢出（{@code INT} 够用几辈子），而是为了档位有意义：
     * 顶档 1000 分之上再涨，用户看到的还是「知心」，而运营统计里会出现
     * 一对聊了三年的 90 万分——那种数字既没法比较也没法展示。
     */
    public static final int SCORE_MAX = 9999;

    /**
     * 主键。走 {@link IdType#ASSIGN_ID}，但<b>自定义 upsert SQL 拿不到自动填充</b>，
     * 插入前必须由服务层用 {@code IdWorker.getId()} 塞进去（与 {@code ProfileVisit} 同一处理）。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 较小的一方用户 ID */
    private Long userAId;

    /** 较大的一方用户 ID */
    private Long userBId;

    /** 亲密度值，只加不减，上限 {@link #SCORE_MAX} */
    private Integer score;

    /** 当前连续聊天天数（火花）；按自然日算，中断即归零重起 */
    private Integer streakDays;

    /** 历史最高连续天数；中断后仍留着，用来显示「曾经 30 天」 */
    private Integer maxStreak;

    /**
     * 最近一次<b>互聊</b>的自然日，判定火花 +1 还是清零的唯一依据。
     *
     * <p>只有消息会写它，点赞评论不写：那两样没有「说过话」，
     * 让它们推进 {@code last_chat_date} 就能靠每天点一个赞刷出永不熄灭的火花。
     */
    private LocalDate lastChatDate;

    /** 累计消息条数（双向合计） */
    private Integer msgCount;

    /** 累计互动数（动态点赞 / 评论，一次加一） */
    private Integer interactCount;

    /** 这个人是不是这一行里的一方。 */
    public boolean involves(Long userId) {
        return userId != null && (userId.equals(userAId) || userId.equals(userBId));
    }

    /** 档位；分值为 null 时按最低档，判定口径见 {@link IntimacyLevel#of(Integer)}。 */
    public IntimacyLevel level() {
        return IntimacyLevel.of(score);
    }

    /** 火花还在不在：今天或昨天聊过才算「没断」（昨天聊过、今天还没聊，不该一早就显示成断了）。 */
    public boolean sparkAlive() {
        if (lastChatDate == null) {
            return false;
        }
        LocalDate today = LocalDate.now();
        return lastChatDate.equals(today) || lastChatDate.equals(today.minusDays(1));
    }
}
