package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.im.common.enums.IntimacyLevel;
import com.im.common.spi.GroupSpi;
import com.im.friend.dto.vo.IntimacyVO;
import com.im.friend.entity.Friend;
import com.im.friend.entity.Intimacy;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.IntimacyMapper;
import com.im.friend.service.IntimacyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 亲密度与火花实现。
 *
 * <p>三条写入全部是单条 upsert，没有「先查再决定插还是改」：两个人同时发消息时那种写法会撞唯一键，
 * 而这里的唯一键 {@code uk_pair(user_a_id, user_b_id)} 正是为了把并发压成一次数据库内部的行锁。
 *
 * <p>共同好友与共同群是<b>读时现算</b>而不是存列：它们随两边的好友/入群变化，
 * 存下来就要在加好友、退群、解散群等七八个地方各补一次维护，漏一处就是一个不会自愈的错数字。
 * 代价是每次看资料卡多两次集合求交——两次都是走索引的 ID 列表，量级是几百，比维护一致性划算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntimacyServiceImpl implements IntimacyService {

    private final IntimacyMapper intimacyMapper;
    private final FriendMapper friendMapper;
    /** 弱依赖：im-group 未装配时共同群恒为 0，亲密度其余部分照常可用 */
    private final ObjectProvider<GroupSpi> groupSpiProvider;

    @Override
    public IntimacyVO view(Long currentUserId, Long targetUserId) {
        if (currentUserId == null || targetUserId == null || currentUserId.equals(targetUserId)) {
            // 看自己没有亲密度可言，返回全 0 比返回 null 省掉前端一条渲染分支
            return empty(targetUserId);
        }
        Intimacy row = intimacyMapper.selectByUsers(lowId(currentUserId, targetUserId),
                highId(currentUserId, targetUserId));
        IntimacyVO.IntimacyVOBuilder builder = IntimacyVO.builder()
                .targetUserId(targetUserId)
                .commonFriendCount(commonFriendCount(currentUserId, targetUserId))
                .commonGroupCount(commonGroupCount(currentUserId, targetUserId));
        if (row == null) {
            // 从没来往过：火花显式置 false，不留 null 让前端去猜「没聊过」和「字段丢了」的区别
            return fill(builder, 0, 0, 0, null, 0, 0).sparkAlive(false).build();
        }
        return fill(builder,
                nvl(row.getScore()), nvl(row.getStreakDays()), nvl(row.getMaxStreak()),
                row.getLastChatDate(), nvl(row.getMsgCount()), nvl(row.getInteractCount()))
                .sparkAlive(row.sparkAlive())
                .build();
    }

    @Override
    public void addChatMessage(Long fromUserId, Long toUserId) {
        if (isInvalidPair(fromUserId, toUserId)) {
            return;
        }
        LocalDate today = LocalDate.now();
        intimacyMapper.upsertOnMessage(IdWorker.getId(),
                lowId(fromUserId, toUserId), highId(fromUserId, toUserId),
                Intimacy.SCORE_PER_MESSAGE, Intimacy.SCORE_MAX, today, today.minusDays(1));
    }

    @Override
    public void addInteract(Long actorId, Long authorId, int scoreDelta) {
        if (isInvalidPair(actorId, authorId)) {
            return;
        }
        intimacyMapper.upsertOnInteract(IdWorker.getId(),
                lowId(actorId, authorId), highId(actorId, authorId),
                scoreDelta, Intimacy.SCORE_MAX);
    }

    /**
     * 把分值相关的六列填进 VO，档位与进度在这里一次算完。
     *
     * <p>进度是「当前档位内走了多少」而不是「占顶档的多少」：按顶档算的话，
     * 一个 1000 分的门槛会让绝大多数人长期停在 3% 的进度条上，看起来像坏掉了。
     */
    private static IntimacyVO.IntimacyVOBuilder fill(IntimacyVO.IntimacyVOBuilder builder,
                                                     int score, int streakDays, int maxStreak,
                                                     LocalDate lastChatDate, int msgCount, int interactCount) {
        IntimacyLevel level = IntimacyLevel.of(score);
        IntimacyLevel next = level.next();
        return builder
                .score(score)
                .level(level.getCode())
                .levelDesc(level.getDesc())
                .nextLevelScore(next == null ? null : next.getMinScore())
                .progress(progress(score, level, next))
                .streakDays(streakDays)
                .maxStreak(maxStreak)
                .lastChatDate(lastChatDate)
                .msgCount(msgCount)
                .interactCount(interactCount);
    }

    /** 顶档恒为 100；档位跨度为 0（理论上不会发生，防手改枚举）也按 100 处理，避免除零。 */
    private static int progress(int score, IntimacyLevel level, IntimacyLevel next) {
        if (next == null) {
            return 100;
        }
        int span = next.getMinScore() - level.getMinScore();
        if (span <= 0) {
            return 100;
        }
        int walked = Math.max(0, score - level.getMinScore());
        return Math.min(100, walked * 100 / span);
    }

    /** 全 0 兜底：档位仍然是「初识」而不是 null，前端不必为「没有档位」再写一套文案。 */
    private static IntimacyVO empty(Long targetUserId) {
        return fill(IntimacyVO.builder().targetUserId(targetUserId)
                        .commonFriendCount(0).commonGroupCount(0),
                0, 0, 0, null, 0, 0)
                .sparkAlive(false)
                .build();
    }

    /** 共同好友：两边各自的正常好友 ID 求交。拉黑的那一侧不算（{@code selectNormalByUserId} 已排除）。 */
    private int commonFriendCount(Long a, Long b) {
        Set<Long> mine = friendIds(a);
        if (mine.isEmpty()) {
            return 0;
        }
        mine.retainAll(friendIds(b));
        return mine.size();
    }

    private Set<Long> friendIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        for (Friend row : friendMapper.selectNormalByUserId(userId)) {
            ids.add(row.getFriendId());
        }
        return ids;
    }

    /** 共同群：两边各自在群里的群 ID 求交；im-group 没装配时按 0 算，不抛异常。 */
    private int commonGroupCount(Long a, Long b) {
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null) {
            return 0;
        }
        List<Long> mine = groupSpi.listJoinedGroupIds(a);
        if (mine == null || mine.isEmpty()) {
            return 0;
        }
        List<Long> theirs = groupSpi.listJoinedGroupIds(b);
        if (theirs == null || theirs.isEmpty()) {
            return 0;
        }
        Set<Long> intersection = new HashSet<>(mine);
        intersection.retainAll(new ArrayList<>(theirs));
        return intersection.size();
    }

    /** 参数缺失或对端为同一人时短路：自己给自己加分只会造出一行毫无意义的记录。 */
    private static boolean isInvalidPair(Long a, Long b) {
        return a == null || b == null || a.equals(b);
    }

    /** a &lt; b 归一化，与 {@code MatchPairMapper#lowId} 同一约定：一行一对人只有在两个方向归到同一行时才成立。 */
    private static Long lowId(Long a, Long b) {
        return a <= b ? a : b;
    }

    private static Long highId(Long a, Long b) {
        return a <= b ? b : a;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
