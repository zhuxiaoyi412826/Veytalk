package com.im.user.spi;

import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.domain.DatingCardDTO;
import com.im.common.enums.DatingEducation;
import com.im.common.enums.DatingGoal;
import com.im.common.enums.DatingIncome;
import com.im.common.enums.DatingMarriage;
import com.im.common.enums.DatingSort;
import com.im.common.spi.DatingQuerySpi;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.util.GeoUtil;
import com.im.user.dto.vo.UserPhotoVO;
import com.im.user.entity.User;
import com.im.user.entity.UserProfile;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserProfileMapper;
import com.im.user.service.DatingPhotoService;
import com.im.user.service.DatingProfileService;
import com.im.user.service.DatingTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link DatingQuerySpi} 实现：交友卡片的组装与隐私收口都只在这一个类里发生。
 *
 * <p>im-friend 拿不到 {@code im_user_profile} / {@code im_user_photo} / {@code im_user_tag}，
 * 所以卡片由本类拼装后整体交出。收口点必须唯一：年龄隐藏、距离模糊化、在线位对陌生人关闭
 * 这三条规则一旦在两个模块各写一遍，就会出现「同一个人在卡池和资料卡上说的不一样」，
 * 而这类不一致的方向永远是「多给了」——漏的那一处不会报错。
 *
 * <p>循环依赖的处理：本类注入 {@link DatingProfileService} / {@link DatingPhotoService} /
 * {@link DatingTagService} 是单向的（它们只依赖 Mapper，不反向依赖 SPI）；
 * {@link FriendRelationSpi} 住在 im-friend，用 {@link ObjectProvider} 弱依赖取，
 * 否则 im-user ↔ im-friend 两个模块在构造期就会互相等死。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatingQuerySpiImpl implements DatingQuerySpi {

    /** 候选池默认与上限口径，越界都由 {@code UserProfileMapper#candidatePool} 夹紧 */
    private static final int DEFAULT_DECK_SIZE = 20;

    /** 附近的人半径区间，口径写在 {@code DatingCandidateQueryDTO} 的字段注释上 */
    private static final int NEARBY_MIN_RADIUS = 500;
    private static final int NEARBY_MAX_RADIUS = 20000;
    /** 半径不超过这个值就用 5 位前缀（格子约 4.9 × 4.9 km），否则用 4 位（约 39 × 19.6 km） */
    private static final int NEARBY_TIGHT_RADIUS = 1000;
    /** geohash 前缀取到几位 */
    private static final int GEO_PRECISION_TIGHT = 5;
    private static final int GEO_PRECISION_WIDE = 4;
    /** 状态检查前多取几倍：不让「查了 20 个有 3 个被封」直接把列表砍成 17 个 */
    private static final int NEARBY_STATUS_PROBE = 3;

    /**
     * 「在线优先」先多取几倍：在线是一时的状态，候选池里真正在线的人可能一个也没有。
     *
     * <p>多取的那批不会白花销：重排完只取回前端要的那几条，超出的行当场丢弃，代价是一次多扫几十行，
     * 好处是「在线优先」不至于把卡池直接清空。
     */
    private static final int ONLINE_FIRST_PROBE = 5;

    /**
     * 「热门」口径里算「近期活跃」的天数窗口。
     *
     * <p>热度真正的那一半（收到多少喜欢）住在 im-friend，本模块只能提供「活跃」这一半：
     * 三天是一个拍出来的经验值，但它足够短——比这更久没上线的人即使很受欢迎，
     * 推给当前用户也换不回回复。
     */
    private static final int HOT_ACTIVE_DAYS = 3;

    /**
     * 互惠置顶最多占卡池的比例（分母为 2）。
     *
     * <p>不是全部：一个全是「喜欢过你的人」的卡池看起来像收件箱而不是推荐流，
     * 互惠是加权因子而不是唯一因子。留出的一半仍走底座序与其余分区。
     */
    private static final int BOOST_HALF_OF_DECK = 2;

    private final UserProfileMapper userProfileMapper;
    private final UserMapper userMapper;
    private final DatingProfileService datingProfileService;
    private final DatingPhotoService datingPhotoService;
    private final DatingTagService datingTagService;
    private final OnlineStatusSpi onlineStatusSpi;
    private final ObjectProvider<FriendRelationSpi> friendRelationSpi;

    /**
     * 推荐流的取数：一句 ORDER BY 打底，再按固定优先级叠四层稳定分区。
     *
     * <p>分区链从低到高（后叠的把先叠的往上顶）：<b>已曝光垫后 → 近几天活跃置顶（热门）
     * → 在线置顶 → 互惠置顶</b>。互惠压在最上是因为：一个不回应的在线陌生人，不如一个
     * 已经喜欢过我的人值得先看；而曝光降权只在最底下，因为它最弱（同一个人被推第三遍仍可能成对）。
     *
     * <p>为什么全是「把一批人整搬前/搬后」而不是打分：四个因子的单位不一样（天数 / 喜欢数 / 在线布尔 /
     * 完资料度），把它们乘上系数加起来得到的就是一个无法向用户解释的数字，而且每个因子都得
     * 在本模块拿到数据才能参与——而喜欢数与曝光这两份数据根本不住在 im-user。
     * 与 f4 定下的契约一致：这里只决定顺序，不决定谁能出现（谁能出现只由筛选条件与 {@code quality_flag} 决定）。
     */
    @Override
    public List<Long> candidateIds(DatingCandidateQueryDTO query) {
        if (query == null || query.getViewerId() == null) {
            // 没有查看者就没有「谁的视角」，距离与隐私位都无从收口，宁可不给也不给一份无视角的池子
            return List.of();
        }
        int limit = clampLimit(query.getLimit());
        if (query.getNearbyRadiusMeters() != null) {
            return nearbyIds(query, limit);
        }
        boolean onlineFirst = Boolean.TRUE.equals(query.getOnlineFirst());
        boolean hot = DatingSort.orDefault(query.getSortBy()) == DatingSort.HOT;
        Set<Long> exposed = new HashSet<>();
        if (query.getDeprioritizeIds() != null) {
            exposed.addAll(query.getDeprioritizeIds());
        }
        // 三个内存分区都只能在「已经取回的那一批」里搬人，不多取一批就可能一个都搬不动；
        // 没有分区时取多少就是多少，不白花销
        boolean needProbe = onlineFirst || hot || !exposed.isEmpty();
        int fetchLimit = needProbe
                ? Math.min(UserProfileMapper.CANDIDATE_MAX, Math.max(limit, limit * ONLINE_FIRST_PROBE))
                : limit;
        // 互惠置顶单独读一次：这批人按底座序很可能排在 LIMIT 之外，混在池子里搬不动
        int boostCap = Math.max(1, limit / BOOST_HALF_OF_DECK);
        List<UserProfile> boostedRows = query.getBoostIds() == null || query.getBoostIds().isEmpty()
                ? List.of()
                : userProfileMapper.candidatePool(query, boostCap, query.getBoostIds());
        List<UserProfile> pool = userProfileMapper.candidatePool(query, fetchLimit);
        if (boostedRows.isEmpty() && pool.isEmpty()) {
            return List.of();
        }
        // 一次批量状态检查覆盖两批人：profile 行不会因为封号被删，只过滤资料列会把封禁用户继续往外推
        List<Long> readIds = new ArrayList<>(boostedRows.size() + pool.size());
        readIds.addAll(idsOf(boostedRows));
        readIds.addAll(idsOf(pool));
        Set<Long> alive = normalUserIds(readIds);

        List<Long> ordered = retainInOrder(idsOf(boostedRows), alive, boostCap);
        List<Long> rest = retainInOrder(idsOf(pool), alive, fetchLimit);
        if (!exposed.isEmpty()) {
            rest = split(rest, exposed, false);
        }
        if (hot) {
            rest = split(rest, recentActiveIds(pool), true);
        }
        if (onlineFirst) {
            rest = onlineFirstOrder(rest, limit);
        }
        Set<Long> taken = new HashSet<>(ordered);
        for (Long userId : rest) {
            if (ordered.size() >= limit) {
                break;
            }
            // 置顶的那批人本来也可能在池子里（他们同时满足筛选与底座序），去重而不是让他占两个坑
            if (taken.add(userId)) {
                ordered.add(userId);
            }
        }
        return ordered;
    }

    /**
     * 把命中 {@code matched} 的人整拨搬到前面（{@code matchedFirst} = false 就搬到后面），
     * 两拨内部都保持原有顺序。
     *
     * <p>不新增打分：分区能保住「搬不动的那部分」的底座序，而重新排序会把 f6 定下的 ORDER BY 口径抵消掉。
     */
    private static List<Long> split(List<Long> ids, Set<Long> matched, boolean matchedFirst) {
        if (matched.isEmpty() || ids.size() <= 1) {
            return ids;
        }
        List<Long> first = new ArrayList<>(ids.size());
        List<Long> second = new ArrayList<>(ids.size());
        for (Long id : ids) {
            (matched.contains(id) == matchedFirst ? first : second).add(id);
        }
        first.addAll(second);
        return first;
    }

    /** 「热门」分区用：资料行里近 {@value #HOT_ACTIVE_DAYS} 天活跃过的人。 */
    private static Set<Long> recentActiveIds(List<UserProfile> pool) {
        LocalDateTime since = LocalDateTime.now().minusDays(HOT_ACTIVE_DAYS);
        Set<Long> result = new HashSet<>(pool.size());
        for (UserProfile row : pool) {
            // last_active_time 为 NULL 算不活跃：这一列是交友这批功能新加的，没有就是没有
            if (row.getLastActiveTime() != null && row.getLastActiveTime().isAfter(since)) {
                result.add(row.getUserId());
            }
        }
        return result;
    }

    private static List<Long> idsOf(List<UserProfile> rows) {
        List<Long> ids = new ArrayList<>(rows.size());
        for (UserProfile row : rows) {
            ids.add(row.getUserId());
        }
        return ids;
    }

    /**
     * 把在线的人排在前面，其余保持候选池已经给出的顺序（<b>稳定分区</b>，不是重新打分）。
     *
     * <p>重排不能推翻上游的排序：在线只决定「同一个人在池里排前还是排后」，
     * 而「谁该被看到」仍由完资料度与筛选条件决定。若改成按在线时长排序，
     * 卡池就会退化成「谁是当前在线的人谁排前面」。
     *
     * <p>候选池本身给不出这个顺序：在线态住在 Redis，而 {@code im_user_profile} 与它是两张存储，
     * 拼不进一句 ORDER BY。
     *
     * <p><b>不截断</b>：截断发生在 {@code candidateIds} 的合并环节，因为互惠置顶还要再往上顶。
     */
    private List<Long> onlineFirstOrder(List<Long> ids, int limit) {
        if (ids.size() <= limit) {
            // 不够装满一页就不必白跑一次 Redis 批量查（在线位只影响顺序，不影响谁能出现）
            return ids;
        }
        Set<Long> online = new HashSet<>(onlineStatusSpi.filterOnline(ids));
        return split(ids, online, true);
    }

    /** 卡池条数的默认值与越界夹紧；真正的上限在 {@code UserProfileMapper#CANDIDATE_MAX}，这里不重复定一个。 */
    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_DECK_SIZE;
        }
        return Math.max(1, Math.min(limit, UserProfileMapper.CANDIDATE_MAX));
    }

    @Override
    public Map<Long, DatingCardDTO> cards(Collection<Long> userIds, Long viewerId) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        Map<Long, User> users = loadUsers(ids);
        if (users.isEmpty()) {
            return Collections.emptyMap();
        }
        // 四次批量读代替「逐人 × 逐字段」：卡池一页十几人，逐个查就是上百条 SQL
        Map<Long, UserProfile> profiles = datingProfileService.findMap(users.keySet());
        Map<Long, UserPhotoVO> covers = datingPhotoService.covers(users.keySet());
        Map<Long, Integer> photoCounts = datingPhotoService.countApprovedByUserIds(users.keySet());
        Map<Long, List<String>> tagNames = datingTagService.namesByUsers(users.keySet());
        Set<Long> onlineIds = new HashSet<>(onlineStatusSpi.filterOnline(users.keySet()));
        Set<Long> friendIds = loadFriendIds(viewerId);
        UserProfile viewer = viewerId == null ? null : datingProfileService.find(viewerId);

        Map<Long, DatingCardDTO> result = new LinkedHashMap<>(users.size());
        for (Map.Entry<Long, User> entry : users.entrySet()) {
            Long userId = entry.getKey();
            User user = entry.getValue();
            UserProfile profile = profiles.get(userId);
            boolean friend = friendIds.contains(userId);
            // 在线位的口径：好友与已配对不受对方开关影响，陌生人只对「没主动关过」的人可见
            boolean online = onlineIds.contains(userId) && (friend || profile == null || profile.onlineVisibleToStranger());
            boolean ageVisible = profile != null && profile.ageVisible();
            result.put(userId, DatingCardDTO.builder()
                    .userId(userId)
                    .nickname(user.getNickname())
                    .avatar(user.getAvatar())
                    .gender(user.getGender())
                    .signature(user.getSignature())
                    .age(ageVisible ? GeoUtil.age(profile.getBirthday()) : null)
                    .heightCm(profile == null ? null : profile.getHeightCm())
                    .occupation(profile == null ? null : profile.getOccupation())
                    .educationDesc(desc(profile == null ? null : profile.getEducation(), DatingEducation::descOf))
                    .incomeDesc(desc(profile == null ? null : profile.getIncome(), DatingIncome::descOf))
                    .marriageDesc(desc(profile == null ? null : profile.getMarriage(), DatingMarriage::descOf))
                    .datingGoalDesc(desc(profile == null ? null : profile.getDatingGoal(), DatingGoal::descOf))
                    .locationText(profile == null ? "" : profile.locationText())
                    .distanceText(profile == null ? null : profile.distanceTextFrom(viewer))
                    .coverUrl(coverUrl(covers.get(userId)))
                    .photoCount(photoCounts.getOrDefault(userId, 0))
                    .tagNames(tagNames.getOrDefault(userId, List.of()))
                    .profileScore(profile == null ? null : profile.getProfileScore())
                    .online(online)
                    .build());
        }
        return result;
    }

    @Override
    public boolean locationReady(Long userId) {
        if (userId == null) {
            return false;
        }
        UserProfile viewer = datingProfileService.find(userId);
        return viewer != null && viewer.hasFreshLocation();
    }

    /**
     * 附近的人：用查看者自己的定位圈半径，按实际距离由近到远排。
     *
     * <p>半径决定 geohash 前缀取几位：前缀格子必须比半径宽一圈，否则跨在格子边界上的人会被
     * 漏掉（不扩展 8 邻居是因为：邻居需要 geohash 解码能力，而误差已被「取粗一档」吸收到大半，
     * 用户看到的又只是一句「3 km 内」）。
     *
     * <p>先算距离再查账号状态：候选窗口有 500 行，而一发的 {@code IN} 查五百个 ID 是白花销；
     * 排完序只取前几倍去校状态，既不漏近处的人也不把批量查拖大。
     *
     * <p>这里的 {@code query} 只取筛选条件与半径：<b>{@code sortBy} 与 {@code onlineFirst} 都被有意忽略</b>——
     * 这一栏的顺序永远是离我最近，把一个会每秒变化的在线态排在这一栏的序里，
     * 用户得到的就是一个刷一次换一次序的列表（筛选口径见 {@code UserProfileMapper#nearbyPool}）。
     */
    private List<Long> nearbyIds(DatingCandidateQueryDTO query, int limit) {
        UserProfile viewer = datingProfileService.find(query.getViewerId());
        if (viewer == null || !viewer.hasFreshLocation()) {
            return List.of();
        }
        Double myLng = doubleOf(viewer.getLng());
        Double myLat = doubleOf(viewer.getLat());
        int radius = Math.max(NEARBY_MIN_RADIUS, Math.min(query.getNearbyRadiusMeters(), NEARBY_MAX_RADIUS));
        String prefix = GeoUtil.geohash(myLng, myLat,
                radius <= NEARBY_TIGHT_RADIUS ? GEO_PRECISION_TIGHT : GEO_PRECISION_WIDE);
        if (prefix == null) {
            // hasFreshLocation 不校坐标合法性（兼容写入前的旧行），算不出前缀就是真的没法按距离找人
            return List.of();
        }
        List<UserProfile> rows = userProfileMapper.nearbyPool(prefix, query);
        List<Distance> nears = new ArrayList<>(rows.size());
        for (UserProfile row : rows) {
            Double lng = doubleOf(row.getLng());
            Double lat = doubleOf(row.getLat());
            if (lng == null || lat == null) {
                continue;
            }
            double meters = GeoUtil.distanceMeters(myLng, myLat, lng, lat);
            if (meters <= radius) {
                nears.add(new Distance(row.getUserId(), meters));
            }
        }
        if (nears.isEmpty()) {
            return List.of();
        }
        nears.sort(Comparator.comparingDouble(Distance::meters).thenComparing(Distance::userId));
        int probe = Math.min(nears.size(), Math.max(1, limit) * NEARBY_STATUS_PROBE);
        List<Long> candidates = new ArrayList<>(probe);
        for (int i = 0; i < probe; i++) {
            candidates.add(nears.get(i).userId());
        }
        return aliveInOrder(candidates, limit);
    }

    /** 按给定顺序保留账号正常的 ID，最多 {@code limit} 个。 */
    private List<Long> aliveInOrder(List<Long> ids, int limit) {
        return retainInOrder(ids, normalUserIds(ids), limit);
    }

    /** 同上，但账号状态集合由调用方给：推荐流一次读两批人（置顶 + 池子），不能查两次 im_user。 */
    private static List<Long> retainInOrder(List<Long> ids, Set<Long> alive, int limit) {
        List<Long> result = new ArrayList<>(Math.min(ids.size(), limit));
        for (Long id : ids) {
            // 保持池内顺序：排序口径归上游，这里过滤掉的就是「不该出现」，不打乱剩下的
            if (alive.contains(id)) {
                result.add(id);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static Double doubleOf(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** 距离排序的中间结果，只为把「算一次距离」与「排一次序」分开写。 */
    private record Distance(Long userId, double meters) {
    }

    @Override
    public boolean discoverable(Long userId, Long viewerId) {
        if (userId == null || viewerId == null || userId.equals(viewerId)) {
            return false;
        }
        if (!normalUserIds(List.of(userId)).contains(userId)) {
            return false;
        }
        UserProfile profile = datingProfileService.find(userId);
        // 没有资料行等于没开过交友功能，谈不上「可被发现」；隐身位只由 UserProfile#stealth 解释一次
        return profile != null && !profile.stealth();
    }

    @Override
    public void touchActive(Long userId) {
        if (userId != null) {
            datingProfileService.touchActive(userId);
        }
    }

    @Override
    public Map<Long, Integer> momentVisibleDays(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 不过一道账号状态检查：这一列只是时间口径，「这个人还能不能被看到」
        // 由动态流那侧的可见性判定与账号状态共同决定，在这里再筛一次只会让两处口径各说一半
        return userProfileMapper.selectMomentVisibleDays(new ArrayList<>(new LinkedHashSet<>(userIds)));
    }

    /**
     * 一次批量读账号，只保留 {@link User#STATUS_NORMAL} 的行。
     *
     * <p>已注销的用户由 MyBatis-Plus 的逻辑删除条件自动挡掉，这里只管封禁。
     */
    private Set<Long> normalUserIds(Collection<Long> userIds) {
        Set<Long> alive = new HashSet<>();
        for (User user : userMapper.selectByIds(new ArrayList<>(userIds))) {
            if (isNormal(user)) {
                alive.add(user.getId());
            }
        }
        return alive;
    }

    /** 卡片只给正常账号渲染，被封禁/已注销的 ID 不出现在结果里（契约 javadoc 同口径）。 */
    private Map<Long, User> loadUsers(Collection<Long> ids) {
        Map<Long, User> result = new LinkedHashMap<>(ids.size());
        for (User user : userMapper.selectByIds(new ArrayList<>(ids))) {
            if (isNormal(user)) {
                result.put(user.getId(), user);
            }
        }
        return result;
    }

    /** status 列为 NULL 按不正常处理：账号状态由后台写入，写坏的行宁可不出现在卡池里。 */
    private static boolean isNormal(User user) {
        return user.getStatus() != null && user.getStatus() == User.STATUS_NORMAL;
    }

    /**
     * 查看者的好友集合。
     *
     * <p>im-friend 未装配（单模块跑测试）时退化为「谁都不是好友」：只会少算在线位，不会多给隐私。
     */
    private Set<Long> loadFriendIds(Long viewerId) {
        if (viewerId == null) {
            return Set.of();
        }
        FriendRelationSpi spi = friendRelationSpi.getIfAvailable();
        if (spi == null) {
            return Set.of();
        }
        try {
            return new HashSet<>(spi.listFriendIds(viewerId));
        } catch (RuntimeException e) {
            log.warn("[交友卡片] 好友集合读取失败，按非好友处理: viewerId={}", viewerId, e);
            return Set.of();
        }
    }

    private static String coverUrl(UserPhotoVO cover) {
        return cover == null ? null : cover.getUrl();
    }

    /** 资料列为 null 时给空串而不是 null：前端直接渲 chip，判断两种「没有」没意义。 */
    private static String desc(Integer code, Function<Integer, String> fn) {
        return code == null ? "" : fn.apply(code);
    }
}
