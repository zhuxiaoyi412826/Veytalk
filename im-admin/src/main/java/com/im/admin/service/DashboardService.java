package com.im.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.admin.config.AdminProperties;
import com.im.admin.dto.po.ReportAggPO;
import com.im.admin.dto.vo.DashboardVO;
import com.im.admin.entity.Report;
import com.im.admin.mapper.ReportMapper;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.AdminUserDTO;
import com.im.common.domain.AdminMetricDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ReportReason;
import com.im.common.enums.ReportStatus;
import com.im.common.spi.AdminMetricsSpi;
import com.im.common.spi.AdminUserSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台看板的聚合读。
 *
 * <h2>两类数字，两个来源</h2>
 *
 * <p>平台数字（用户数、今日消息数、在播场数）<b>一条都不由本模块算</b>：星型架构下 im-admin
 * 读不到别的模块的表，只能各模块经 {@code AdminMetricsSpi} 把数字递过来，看板按模块分区显示。
 * 举报自己的统计（待处理数、今日新增、七天趋势、理由分布、被举报榜）才是本模块的表，三条 GROUP BY 直接算。
 *
 * <p>代价写在接口上而不是这里：某个模块没实现 SPI，看板就少一个分区。
 * <b>少一个分区比编一个 0 诚实</b>——「在播 0 场」会让管理员以为直播出了故障。
 *
 * <h2>缓存为什么值得</h2>
 *
 * <p>一次刷新要跑各模块的 COUNT 加本模块的三条 GROUP BY，而看板往往是长时间盯着看的页面。
 * 默认缓存 60 秒（{@code im.admin.dashboard-cache-seconds}，设 0 即关闭）。
 * 于是响应里必须带 {@code cachedAt} 与 {@code staleSeconds}：<b>滞后要明写出来</b>，
 * 否则管理员会拿着两分钟前的「待处理 3 条」去决定要不要下班。
 * 处置动作之后仍然能看到最新队列，因为队列列表本身不缓存，只有汇总数字缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 趋势窗口（天）：七天是一个运营周期的自然长度，再长就看不出今天的变化 */
    private static final int TREND_DAYS = 7;
    /** 被举报榜取前几位 */
    private static final int TOP_USER_MAX = 10;

    private final ReportMapper reportMapper;
    private final AdminProperties adminProperties;
    private final RedisUtil redisUtil;
    private final UserQuerySpi userQuerySpi;
    private final ObjectProvider<AdminMetricsSpi> metricsSpiProvider;
    /** 被举报榜那一列的账号状态（已封 / 正常）；用户模块缺席时那一列为空，前端显示「未知」 */
    private final ObjectProvider<AdminUserSpi> adminUserSpiProvider;

    /**
     * 取看板数据。
     *
     * @param force 忽略缓存重新聚合——页面上的「刷新」按钮必须真能刷新，
     *              否则按钮只是在重放缓存，管理员会以为数字没变就是队列没变
     */
    public DashboardVO dashboard(boolean force) {
        int ttl = adminProperties.getDashboardCacheSeconds();
        String key = RedisKeys.adminDashboard();
        if (ttl > 0 && !force) {
            DashboardVO cached = redisUtil.getObject(key, DashboardVO.class);
            if (cached != null) {
                return cached;
            }
        }
        DashboardVO vo = aggregate(ttl);
        if (ttl > 0) {
            redisUtil.setObject(key, vo, Duration.ofSeconds(ttl));
        }
        return vo;
    }

    private DashboardVO aggregate(int ttlSeconds) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime trendStart = todayStart.minusDays(TREND_DAYS - 1L);

        DashboardVO vo = new DashboardVO();
        vo.setSections(buildSections());
        vo.setPendingReports(countByStatus(List.of(ReportStatus.PENDING.getCode(), ReportStatus.ACCEPTED.getCode()),
                null));
        vo.setTodayReports(countByStatus(null, todayStart));
        vo.setTrend(buildTrend(trendStart));
        vo.setReasons(buildReasons(trendStart));
        vo.setTopReportedUsers(buildTopUsers(trendStart));
        vo.setCachedAt(LocalDateTime.now());
        vo.setStaleSeconds(Math.max(0, ttlSeconds));
        return vo;
    }

    /**
     * 各模块贡献的分区。
     *
     * <p>单个模块取数失败不影响整屏：那一格显示「—」并在 hint 里带上失败原因。
     * 让一个模块的异常把整块看板打空，管理员看到的是「数据全没」，那比看到一格空数字更吓人。
     */
    private List<DashboardVO.Section> buildSections() {
        List<DashboardVO.Section> sections = new ArrayList<>();
        for (AdminMetricsSpi spi : metricsSpiProvider.orderedStream().toList()) {
            DashboardVO.Section section = new DashboardVO.Section();
            section.setSource(spi.source());
            section.setTitle(spi.sectionTitle());
            try {
                List<AdminMetricDTO> metrics = spi.metrics();
                section.setMetrics(metrics == null ? List.of() : metrics);
            } catch (Exception e) {
                log.warn("[管理后台] 看板分区取数失败: source={}, err={}", spi.source(), e.getMessage());
                section.setMetrics(List.of(AdminMetricDTO.builder()
                        .key("error")
                        .label("数据获取失败")
                        .value(null)
                        .hint(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        .build()));
            }
            sections.add(section);
        }
        return sections;
    }

    /**
     * 待处理数与今日新增都走 {@code idx_status_time}；今日新增不带状态条件，
     * 因为「今天报了多少条」和它们后来有没有被处理掉是两件事。
     */
    private Long countByStatus(List<Integer> statuses, LocalDateTime since) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(Report::getStatus, statuses);
        }
        if (since != null) {
            wrapper.ge(Report::getCreateTime, since);
        }
        Long count = reportMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    /**
     * 近七天趋势：SQL 不补零，缺数据的那天在这里补——日历归 Java 管，数据库只管有行的那些天。
     */
    private List<DashboardVO.TrendPoint> buildTrend(LocalDateTime since) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (ReportAggPO.Day row : reportMapper.aggregateByDay(since)) {
            if (row.getDay() != null) {
                counts.put(row.getDay(), row.getCount());
            }
        }
        List<DashboardVO.TrendPoint> points = new ArrayList<>(TREND_DAYS);
        LocalDate start = since.toLocalDate();
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = start.plusDays(i);
            DashboardVO.TrendPoint point = new DashboardVO.TrendPoint();
            point.setDay(day);
            point.setCount(counts.getOrDefault(day, 0L));
            points.add(point);
        }
        return points;
    }

    private List<DashboardVO.ReasonSlice> buildReasons(LocalDateTime since) {
        List<DashboardVO.ReasonSlice> slices = new ArrayList<>();
        for (ReportAggPO.Reason row : reportMapper.aggregateByReason(since)) {
            DashboardVO.ReasonSlice slice = new DashboardVO.ReasonSlice();
            slice.setCode(row.getReasonCode());
            ReportReason reason = ReportReason.of(row.getReasonCode());
            slice.setName(reason == null ? row.getReasonCode() : reason.getDesc());
            slice.setCount(row.getCount());
            slices.add(slice);
        }
        return slices;
    }

    /**
     * 被举报榜。昵称经 {@code UserQuerySpi} 批量取；已注销的账号取不到人，
     * 就写「未知用户」而不是从榜单里删掉——那一条举报仍然在账上。
     */
    private List<DashboardVO.TopUser> buildTopUsers(LocalDateTime since) {
        List<ReportAggPO.UserCount> rows = reportMapper.aggregateTopReportedUsers(since, TOP_USER_MAX);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = rows.stream()
                .map(ReportAggPO.UserCount::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserBriefDTO> briefs = userIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(userIds);
        Map<Long, Integer> statuses = statusOf(userIds);
        List<DashboardVO.TopUser> topUsers = new ArrayList<>(rows.size());
        for (ReportAggPO.UserCount row : rows) {
            DashboardVO.TopUser user = new DashboardVO.TopUser();
            user.setUserId(row.getUserId());
            user.setCount(row.getCount());
            user.setStatus(statuses.get(row.getUserId()));
            UserBriefDTO brief = briefs.get(row.getUserId());
            user.setNickname(brief == null ? "未知用户" : brief.getNickname());
            user.setAvatar(brief == null ? null : brief.getAvatar());
            topUsers.add(user);
        }
        return topUsers;
    }

    /**
     * 批量取账号状态（最多十个 ID，一次调用）。
     *
     * <p>为什么不用 {@code UserQuerySpi} 的返回值：那份精简资料为了不给普通接口暴露账号状态，
     * 本来就没有 {@code status} 字段；而「这个人是不是已经被封了」正是榜单上最需要的信息，
     * 只能走后台专用的 {@code AdminUserSpi}。
     */
    private Map<Long, Integer> statusOf(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        AdminUserSpi spi = adminUserSpiProvider.getIfAvailable();
        if (spi == null) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (AdminUserDTO user : spi.listByIds(userIds)) {
            if (user != null && user.getUserId() != null) {
                result.put(user.getUserId(), user.getStatus());
            }
        }
        return result;
    }

    /**
     * 清掉缓存。处置完工单后调用，让「待处理」那一格立刻反映真实队列，
     * 而不是等下一个 TTL 周期——管理员刚处理完一条却还看见原来的数字，会以为处置没生效。
     */
    public void evictCache() {
        redisUtil.delete(RedisKeys.adminDashboard());
    }
}
