package com.im.user.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.enums.DatingSort;
import com.im.user.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交友资料卡 Mapper。
 *
 * <p>单人读写按主键 {@code user_id} 走；<b>候选池</b>那一类「一批人 + 条件过滤」的读法也留在这里
 * 而不是挪到 im-friend：{@code im_user_profile} 归本模块，条件与索引（{@code idx_discover_score}、
 * {@code idx_geohash}、{@code idx_active}）怎么用只有资料的所有者知道。im-friend 经
 * {@code DatingQuerySpi#candidateIds} 拿 ID，把自己那张表里算出的排除名单传进来。
 *
 * <p>入参统一是 {@link DatingCandidateQueryDTO}：<b>「要什么」由 im-friend 说，「怎么进 SQL」由本模块定</b>。
 * 年龄要变成出生日期区间、学历要变成 {@code >=}、性别要去查 {@code im_user}——这些换算一旦搬到
 * im-friend，就是让不持有这些列的模块替持有者决定口径，两边会各自开始漂移。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /** 一次候选池查询最多返回这么多行：卡池一页十几张，放到 50 是上限而不是目标 */
    int CANDIDATE_MAX = 50;

    /**
     * 附近的人一次扫描多少行：geohash 前缀只能圈出「同一个格子」，精确距离要把行取回内存现算，
     * 不封顶就会在热门城市里捞回几千行再全部丢掉（排序口径见 {@link #nearbyPool}）。
     */
    int NEARBY_SCAN_MAX = 500;

    /**
     * 滑动匹配与推荐流共用的候选池。
     *
     * <p>默认（{@code sortBy} 为 recommend / hot 或无法识别）排序口径是「资料越全越先出现，
     * 同分越活跃越先出现」：这只是<b>底座序</b>，互惠与曝光池那两档在
     * {@code DatingQuerySpiImpl} 里以稳定分区叠在上面（它们要用的列与表不在这里）。
     *
     * <p>{@code last_active_time} 为 NULL 的老数据在 DESC 下自然排最后（MySQL 把 NULL 当最小），
     * 不需要额外写 {@code IS NULL} 分支；末位再按 userId 兜一个稳定序，否则同分行每次翻页都可能重排。
     *
     * @param query      查看者、排除名单与筛选条件；{@code viewerId} 为 null 时调用方不该走到这里
     * @param fetchLimit 本次实际取回的行数，可以大于 {@code query.limit}——「在线优先」与「互惠/曝光分区」
     *                   都要先多取一批再在内存里重排（在线态在 Redis、喜欢流水在 im-friend，
     *                   都进不了这里的 ORDER BY）；仍会夹到 {@code [1, }{@link #CANDIDATE_MAX}{@code ]}
     */
    default List<UserProfile> candidatePool(DatingCandidateQueryDTO query, int fetchLimit) {
        return candidatePool(query, fetchLimit, null);
    }

    /**
     * 同上，但只在 {@code restrictToIds} 这批人里取——为「推荐流的互惠置顶」准备。
     *
     * <p>为什么要单开一次读而不是把 {@code boostIds} 直接叠在正常池子上：置顶的人如果按底座序
     * 排在 {@code LIMIT} 之外，就永远取不回来，「喜欢过我的人优先」就会变成一个只在人少时生效的
     * 开关。多一次 {@code IN} 查询换来的是这条规则真的算得出来。
     *
     * <p><b>WHERE 与 ORDER BY 与正常池子完全一致</b>（同一个 {@code baseWrapper} +
     * {@code applyFilters} + {@code applySort}），所以这批人仍然要满足我设的年龄/城市/学历等条件——
     * 「喜欢我」不等于「可以绕过我的筛选」，筛选是用户明确表达过的边界。
     */
    default List<UserProfile> candidatePool(DatingCandidateQueryDTO query, int fetchLimit, Collection<Long> restrictToIds) {
        int safeLimit = Math.max(1, Math.min(fetchLimit, CANDIDATE_MAX));
        LambdaQueryWrapper<UserProfile> wrapper = baseWrapper(query);
        if (restrictToIds != null && !restrictToIds.isEmpty()) {
            wrapper.in(UserProfile::getUserId, restrictToIds);
        }
        applyFilters(wrapper, query);
        applySort(wrapper, DatingSort.orDefault(query.getSortBy()));
        // limit 已经被夹成 int 常量区间，拼进 SQL 不存在注入面；用 #{ } 反而要为此写一个 @Select
        wrapper.last("LIMIT " + safeLimit);
        return selectList(wrapper);
    }

    /**
     * 「附近的人」的粗筛：取与查看者在同一个 geohash 前缀格子里、且定位仍在采信期内的人。
     *
     * <p><b>这里不按距离排序</b>：精确距离要拿到坐标回内存用 {@code GeoUtil.distanceMeters} 逐行算，
     * MySQL 这一侧只负责把「可能是附近的」缩小到几百行。预排序选 {@code location_time} 倒序而不是分数倒序：
     * 一个 90 分但三个月前定位的人实际上不会在附近，让这种行占满扫描窗口会让真正在附近的人一个都进不来。
     *
     * <p>排序也<b>不看 {@code sortBy}</b>：这一栏的顺序永远是「离我最近」，偏好里的排序口径是给推荐流用的，
     * 两套顺序同时生效只会让结果看起来像随机；但筛选条件（年龄 / 城市 / 学历…）照旧生效。
     *
     * @param geohashPrefix 查看者 geohash 截前缀（位数由调用方按半径选，见 {@code DatingQuerySpiImpl}）
     */
    default List<UserProfile> nearbyPool(String geohashPrefix, DatingCandidateQueryDTO query) {
        LambdaQueryWrapper<UserProfile> wrapper = baseWrapper(query)
                .likeRight(UserProfile::getGeohash, geohashPrefix)
                .ge(UserProfile::getLocationTime,
                        LocalDateTime.now().minusDays(UserProfile.LOCATION_VALID_DAYS));
        applyFilters(wrapper, query);
        return selectList(wrapper.orderByDesc(UserProfile::getLocationTime)
                .last("LIMIT " + NEARBY_SCAN_MAX));
    }

    /**
     * 批量取「动态几天可见」，只拉两列。
     *
     * <p>用 {@code select} 限定列而不是把整行拉回来：动态流一页几十位作者，
     * 而资料行有三十多列（包含经纬度与出生日期），为了一个 TINYINT 把它们全读一遍不值。
     *
     * @return key 为 userId；<b>没有资料行的人不在结果里</b>（调用方按最严的一档处理）
     */
    default Map<Long, Integer> selectMomentVisibleDays(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserProfile> rows = selectList(Wrappers.<UserProfile>lambdaQuery()
                .select(UserProfile::getUserId, UserProfile::getMomentVisibleDays)
                .in(UserProfile::getUserId, userIds));
        Map<Long, Integer> result = new HashMap<>(rows.size());
        for (UserProfile row : rows) {
            result.put(row.getUserId(), row.getMomentVisibleDays());
        }
        return result;
    }

    /** 两条候选池读法共用的骨架：可被发现、不是查看者本人、不在排除名单里。 */
    private static LambdaQueryWrapper<UserProfile> baseWrapper(DatingCandidateQueryDTO query) {
        Collection<Long> excludeIds = query.getExcludeIds();
        boolean hasExclude = excludeIds != null && !excludeIds.isEmpty();
        LambdaQueryWrapper<UserProfile> wrapper = Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getDiscoverable, UserProfile.SHOW)
                .ne(UserProfile::getUserId, query.getViewerId())
                .notIn(hasExclude, UserProfile::getUserId, excludeIds);
        if (Boolean.TRUE.equals(query.getExcludeLowQuality())) {
            // 直接用 ne 而不补 IS NULL：这一列是 NOT NULL DEFAULT 0，库里不会有空值
            wrapper.ne(UserProfile::getQualityFlag, UserProfile.QUALITY_LOW);
        }
        return wrapper;
    }

    /**
     * 把「筛选与偏好」摊成 WHERE 条件。<b>约定：null 与 0 都是不限</b>，对应的那一条整条不拼。
     *
     * <p>拼进去的比较符全是 {@code >=} / {@code <=} / {@code =} / {@code LIKE}，值走占位符；
     * 唯一拼字符串的是性别那个子查询——它只在取值为 1 或 2 时才走到这一行，是一个已经收敛过的 int，
     * 没有注入面（与 {@code LIMIT} 内联同理）。
     *
     * <p><b>填了区间就会排除没填的人</b>：{@code birthday}/{@code height_cm} 为 NULL 的行在比较下天然不成立。
     * 这是有意的严格口径——「不限身高」与「身高未知」是两件事，前者是用户明确不想筛，后者是他没告诉过任何人；
     * 把未知算进已知，筛选结果就不可信了。
     */
    private static void applyFilters(LambdaQueryWrapper<UserProfile> wrapper, DatingCandidateQueryDTO query) {
        Integer gender = query.getTargetGender();
        if (gender != null && (gender == 1 || gender == 2)) {
            // 性别列在 im_user 而不在本表，而 im_user 同样归本模块，所以这一步换算留在这里
            wrapper.inSql(UserProfile::getUserId, "SELECT id FROM im_user WHERE deleted = 0 AND gender = " + gender);
        }
        Integer ageMin = query.getAgeMin();
        if (ageMin != null && ageMin > 0) {
            // 「至少 ageMin 岁」= 出生日期不晚于今天往前数 ageMin 年（生日当天即满岁，故取 <=）
            wrapper.le(UserProfile::getBirthday, LocalDate.now().minusYears(ageMin));
        }
        Integer ageMax = query.getAgeMax();
        if (ageMax != null && ageMax > 0) {
            // 「不超过 ageMax 岁」= 出生日期晚于 ageMax+1 年前的那一天：用开区间就不会把刚满 ageMax 岁的人算老一岁
            wrapper.gt(UserProfile::getBirthday, LocalDate.now().minusYears(ageMax + 1L));
        }
        Integer heightMin = query.getHeightMin();
        if (heightMin != null && heightMin > 0) {
            wrapper.ge(UserProfile::getHeightCm, heightMin);
        }
        Integer heightMax = query.getHeightMax();
        if (heightMax != null && heightMax > 0) {
            wrapper.le(UserProfile::getHeightCm, heightMax);
        }
        Integer minEducation = query.getMinEducation();
        if (minEducation != null && minEducation > 0) {
            // 学历码是升序设计（见 DatingEducation），所以直接 >=；未填（0）的人被这一条自然排除
            wrapper.ge(UserProfile::getEducation, minEducation);
        }
        Integer marriage = query.getTargetMarriage();
        if (marriage != null && marriage > 0) {
            wrapper.eq(UserProfile::getMarriage, marriage);
        }
        String city = query.getCity();
        if (city != null && !city.isBlank()) {
            // 包含而不是等值：资料里的城市是自由文本，等值会让「杭州」与「杭州市」互不可见
            wrapper.like(UserProfile::getCity, city.trim());
        }
    }

    /**
     * 排序口径落到 ORDER BY。
     *
     * <p>三个能进 SQL 的口径各自占用一个列：{@code recommend} 以完资料度为主、{@code active} 以
     * 活跃时间为主、{@code new} 以资料行建立时间为主。{@code hot} <b>不在这里</b>：它的主因子
     * 「收到多少喜欢」住在 im-friend 的 {@code im_match_like}，星型架构下本模块读不到，
     * 所以下面与无法识别的值一起退回 {@code recommend} 底座，热度由调用方用
     * {@code boostIds} 与 {@code DatingQuerySpiImpl} 的活跃分区叠加。
     *
     * <p>{@code new} 排的是<b>本表</b>的 {@code create_time} 而不是 {@code im_user.create_time}：
     * 后者要多 join 一张表才能进 ORDER BY，而这批行的建立时间在迁移当天对存量账号是同一个值，
     * 并列时自然落到第二序的活跃时间上——「最近加入的优先，没有新人就最近活跃的」，
     * 这仍是冷启动曝光池要的那个序。
     */
    private static void applySort(LambdaQueryWrapper<UserProfile> wrapper, DatingSort sort) {
        if (sort == DatingSort.ACTIVE) {
            wrapper.orderByDesc(UserProfile::getLastActiveTime)
                    .orderByDesc(UserProfile::getProfileScore)
                    .orderByDesc(UserProfile::getUserId);
            return;
        }
        if (sort == DatingSort.NEW) {
            wrapper.orderByDesc(UserProfile::getCreateTime)
                    .orderByDesc(UserProfile::getLastActiveTime)
                    .orderByDesc(UserProfile::getUserId);
            return;
        }
        wrapper.orderByDesc(UserProfile::getProfileScore)
                .orderByDesc(UserProfile::getLastActiveTime)
                .orderByDesc(UserProfile::getUserId);
    }
}
