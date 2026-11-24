package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.im.common.domain.DatingCardDTO;
import com.im.common.enums.ProfileVisitType;
import com.im.common.spi.DatingQuerySpi;
import com.im.friend.dto.vo.VisitorVO;
import com.im.friend.entity.MatchPair;
import com.im.friend.entity.ProfileVisit;
import com.im.friend.mapper.MatchLikeMapper;
import com.im.friend.mapper.MatchPairMapper;
import com.im.friend.mapper.ProfileVisitMapper;
import com.im.friend.service.ProfileVisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 访客记录实现。
 *
 * <p>一次来访一行 upsert、读时按人聚合，两件事都是<b>纯索引</b>：写走
 * {@code uk_visitor_visited_date}，读走 {@code idx_visited_last(visited_id, last_time)}。
 * 这也是「一人一天一行」换来的好处——一张每次都插新行的表要回答「谁来过」必须全表扫。
 *
 * <p>卡片一律现取 {@link DatingQuerySpi#cards}：本模块存不下、也不该存对方的资料。
 * 于是「TA 看完资料卡之后把年龄藏了」这种情况会自动反映在今天这一屏里，而不需要额外口径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileVisitServiceImpl implements ProfileVisitService {

    private final ProfileVisitMapper profileVisitMapper;
    private final DatingQuerySpi datingQuerySpi;
    private final MatchLikeMapper matchLikeMapper;
    private final MatchPairMapper matchPairMapper;

    @Override
    public void record(Long visitorId, Long visitedId, ProfileVisitType type) {
        if (visitorId == null || visitedId == null || visitorId.equals(visitedId)) {
            // 自己看自己每天都发生（保存资料后回显、刷新页面），记进去等于让访客列表永远第一条是我
            return;
        }
        ProfileVisit row = new ProfileVisit();
        // 主键自己发号：自定义 SQL 拿不到 MyBatis-Plus 的自动填充（同 MatchServiceImpl 记曝光时的约定）
        row.setId(IdWorker.getId());
        row.setVisitorId(visitorId);
        row.setVisitedId(visitedId);
        // 自然日由服务端定：「一天一行」的这个「一天」只能有一个时钟，信客户端时间就会出现
        // 凌晨把昨天的访问记到今天、times 因此白加一次
        row.setVisitDate(LocalDate.now());
        row.setViewType(type == null ? ProfileVisitType.PROFILE_CARD.getCode() : type.getCode());
        // times / first_time / last_time 都不塞：新行吃建表默认值，命中已有行由 ON DUPLICATE 累加与推后
        profileVisitMapper.upsertVisit(row);
    }

    @Override
    public List<VisitorVO> recentVisitors(Long userId) {
        if (userId == null) {
            return List.of();
        }
        // 减 29 而不是 30：查询里的 ge 是闭区间，含今天算满 30 个自然日
        LocalDate since = LocalDate.now().minusDays(ProfileVisit.LIST_WINDOW_DAYS - 1L);
        List<ProfileVisit> rows = profileVisitMapper.selectRecentVisits(userId, since,
                ProfileVisitMapper.WINDOW_SCAN_MAX);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 行序就是 last_time 倒序，LinkedHashMap 因此天然把「最近来过的人」排在前面，
        // 截断到 VISITOR_MAX 时截掉的是最久没来的人而不是随机一批
        Map<Long, List<ProfileVisit>> grouped = new LinkedHashMap<>();
        for (ProfileVisit row : rows) {
            grouped.computeIfAbsent(row.getVisitorId(), key -> new ArrayList<>()).add(row);
        }
        List<Long> visitorIds = new ArrayList<>(grouped.keySet());
        if (visitorIds.size() > ProfileVisitMapper.VISITOR_MAX) {
            visitorIds = visitorIds.subList(0, ProfileVisitMapper.VISITOR_MAX);
        }
        Map<Long, DatingCardDTO> cards = datingQuerySpi.cards(visitorIds, userId);
        Set<Long> liked = new HashSet<>(matchLikeMapper.selectLikedTargets(userId, visitorIds));
        Map<Long, MatchPair> pairs = activePairMap(userId);

        List<VisitorVO> items = new ArrayList<>(visitorIds.size());
        for (Long visitorId : visitorIds) {
            DatingCardDTO card = cards.get(visitorId);
            if (card == null) {
                // 卡片为 null 只剩「被封禁/注销」或「批量读与拼装之间被删号」：少一条比渲染一张空卡可信
                continue;
            }
            List<ProfileVisit> mine = grouped.get(visitorId);
            // 取倒序结果的第一行 = 这个人最近的那一天，view_type 与 last_time 都以它为准
            ProfileVisit latest = mine.get(0);
            MatchPair pair = pairs.get(visitorId);
            items.add(VisitorVO.builder()
                    .visitorId(visitorId)
                    .card(card)
                    .lastTime(latest.getLastTime())
                    .visitDays(mine.size())
                    .visitTimes(sumTimes(mine))
                    .viewType(latest.getViewType())
                    .viewDesc(ProfileVisitType.descOf(latest.getViewType()))
                    .liked(liked.contains(visitorId))
                    .paired(pair != null)
                    .conversationId(pair == null ? null : pair.getConversationId())
                    .build());
        }
        return items;
    }

    /** 窗口内累计次数；列非空，这里的 null 只可能是未来有人改了实体。 */
    private static int sumTimes(List<ProfileVisit> rows) {
        int times = 0;
        for (ProfileVisit row : rows) {
            times += row.getTimes() == null ? 0 : row.getTimes();
        }
        return times;
    }

    /** peer → 配对中关系；已解除的不进表，解除不等于拉黑（同 NearbyServiceImpl）。 */
    private Map<Long, MatchPair> activePairMap(Long userId) {
        Map<Long, MatchPair> map = new HashMap<>();
        for (MatchPair pair : matchPairMapper.selectActiveByUser(userId)) {
            Long peer = pair.peerId(userId);
            if (peer != null) {
                map.put(peer, pair);
            }
        }
        return map;
    }
}
