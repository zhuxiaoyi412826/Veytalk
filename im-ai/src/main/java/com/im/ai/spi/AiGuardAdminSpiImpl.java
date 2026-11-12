package com.im.ai.spi;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.ai.entity.AiGuard;
import com.im.ai.entity.AiUsage;
import com.im.ai.mapper.AiGuardMapper;
import com.im.ai.mapper.AiUsageMapper;
import com.im.ai.service.AiGuardService;
import com.im.common.api.PageResult;
import com.im.common.config.ImProperties;
import com.im.common.domain.AdminAiOverviewDTO;
import com.im.common.domain.AdminAiUsageDTO;
import com.im.common.domain.AdminAiUsageQueryDTO;
import com.im.common.domain.AdminAiUserDTO;
import com.im.common.domain.AdminAiUserQueryDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.AdminAiGuardSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 用量管控契约的实现（{@link AdminAiGuardSpi}），读 {@code im_ai_usage} / {@code im_ai_guard}。
 *
 * <p>概览与聚合的数字都来自<b>单条</b>带 {@code CASE} 的聚合或一次 {@code GROUP BY user_id}，
 * 理由与 {@code InterviewAdminSpiImpl} 一样：流水表除了索引就是全表，把同一趟扫描的代价
 * 付两次是浪费。时间口径统一用数据库 {@code CURDATE()}——「今日」只该有一个权威时钟。
 *
 * <p>封禁写动作走主键 {@code selectById + insert/updateById}：一行一人、没封过就没有行，
 * upsert 语义两条语句搞定，不需要 MySQL 的 ON DUPLICATE 方言。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGuardAdminSpiImpl implements AdminAiGuardSpi {

    /** 单页上限，与面试 / 直播后台同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 烧钱榜取前几名 */
    private static final int TOP_USERS = 10;
    private static final String DELETED_NICKNAME = "已注销用户";
    private static final String DEFAULT_BAN_REASON = "违反使用规范，AI 功能被封禁";

    private final AiUsageMapper usageMapper;
    private final AiGuardMapper guardMapper;
    private final ImProperties imProperties;
    private final UserQuerySpi userQuerySpi;

    @Override
    public AdminAiOverviewDTO overview() {
        Map<String, Object> row = firstMap(usageMapper.selectMaps(Wrappers.<AiUsage>query()
                .select("COUNT(*) AS totalCalls",
                        "COALESCE(SUM(CASE WHEN create_time >= CURDATE() THEN 1 ELSE 0 END), 0) AS todayCalls",
                        "COALESCE(SUM(CASE WHEN success = 1 THEN duration_ms ELSE 0 END), 0) AS totalMs",
                        "COALESCE(SUM(CASE WHEN create_time >= CURDATE() THEN duration_ms ELSE 0 END), 0) AS todayMs",
                        "COUNT(DISTINCT user_id) AS activeUsers",
                        "COALESCE(COUNT(DISTINCT CASE WHEN create_time >= CURDATE() THEN user_id END), 0) AS todayActiveUsers")));
        ImProperties.AiGuard config = imProperties.getAiGuard();
        return AdminAiOverviewDTO.builder()
                .guardEnabled(config.isEnabled())
                .trialSeconds(config.getTrialSeconds())
                .totalCalls(asLong(row.get("totalCalls")))
                .todayCalls(asLong(row.get("todayCalls")))
                .totalMs(asLong(row.get("totalMs")))
                .todayMs(asLong(row.get("todayMs")))
                .activeUsers(asLong(row.get("activeUsers")))
                .todayActiveUsers(asLong(row.get("todayActiveUsers")))
                .bannedUsers(guardMapper.selectCount(Wrappers.<AiGuard>lambdaQuery()
                        .eq(AiGuard::getBanned, 1)))
                .topUsers(topUsers())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public PageResult<AdminAiUserDTO> pageUsers(AdminAiUserQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);

        // bannedOnly 的候选集就是封禁表本身：全员也才一人一行，IN 列表不会失控
        List<Long> bannedIds = null;
        if (Boolean.TRUE.equals(query.getBannedOnly())) {
            bannedIds = bannedUserIds();
            if (bannedIds.isEmpty()) {
                return PageResult.empty(current, size);
            }
        }
        // 聚合翻页走手写 SQL：往实体映射会把没有对应字段的聚合别名静默丢成 0
        Page<Map<String, Object>> page = new Page<>(current, size);
        IPage<Map<String, Object>> result = usageMapper.pageUserAgg(page, query.getUserId(), bannedIds);
        List<Map<String, Object>> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }

        Set<Long> userIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            userIds.add(asLong(row.get("user_id")));
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
        Map<Long, AiGuard> guards = guardMap(userIds);

        long budgetMs = imProperties.getAiGuard().getTrialSeconds() * 1000L;
        boolean guardEnabled = imProperties.getAiGuard().isEnabled();
        List<AdminAiUserDTO> records = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long userId = asLong(row.get("user_id"));
            AiGuard guard = guards.get(userId);
            boolean banned = guard != null && Integer.valueOf(1).equals(guard.getBanned());
            long usedMs = asLong(row.get("total_ms"));
            // admin 豁免在这一屏的体现：角色信息不在本模块，不跨模块查角色；
            // unlimited 只反映总开关，admin 的行靠「同预算下耗时还在涨」自然识别
            boolean unlimited = !guardEnabled;
            records.add(AdminAiUserDTO.builder()
                    .userId(userId)
                    .userName(displayOf(briefs.get(userId)))
                    .totalCalls(asLong(row.get("total_calls")))
                    .successCalls(asLong(row.get("success_calls")))
                    .totalMs(usedMs)
                    .todayMs(asLong(row.get("today_ms")))
                    .lastUsedTime(asDateTime(row.get("last_used_time")))
                    .budgetMs(budgetMs)
                    .remainingMs(unlimited ? -1L : Math.max(0L, budgetMs - usedMs))
                    .unlimited(unlimited)
                    .banned(banned)
                    .banReason(banned ? guard.getReason() : null)
                    .build());
        }
        long total = result.getTotal();
        return PageResult.of(records, total, current, size);
    }

    @Override
    public PageResult<AdminAiUsageDTO> pageUsage(AdminAiUsageQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String scene = trimTo(query.getScene(), 40);

        Page<AiUsage> page = usageMapper.selectPage(new Page<>(current, size),
                Wrappers.<AiUsage>lambdaQuery()
                        .eq(query.getUserId() != null, AiUsage::getUserId, query.getUserId())
                        .eq(scene != null, AiUsage::getScene, scene)
                        .eq(query.getSuccess() != null, AiUsage::getSuccess,
                                query.getSuccess() != null && query.getSuccess() ? 1 : 0)
                        .orderByDesc(AiUsage::getCreateTime)
                        .orderByDesc(AiUsage::getId));
        List<AiUsage> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> userIds = new HashSet<>();
        for (AiUsage row : rows) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
        List<AdminAiUsageDTO> records = new ArrayList<>(rows.size());
        for (AiUsage row : rows) {
            records.add(AdminAiUsageDTO.builder()
                    .usageId(row.getId())
                    .userId(row.getUserId())
                    .userName(displayOf(briefs.get(row.getUserId())))
                    .scene(row.getScene())
                    .durationMs(row.getDurationMs())
                    .success(row.getSuccess())
                    .errorMsg(row.getErrorMsg())
                    .createTime(row.getCreateTime())
                    .build());
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public void ban(Long userId, String reason, Long operatorId) {
        if (userId == null) {
            return;
        }
        String text = trimTo(reason, 200);
        AiGuard guard = guardMapper.selectById(userId);
        if (guard == null) {
            guardMapper.insert(AiGuard.builder()
                    .userId(userId)
                    .banned(1)
                    .reason(text == null ? DEFAULT_BAN_REASON : text)
                    .updateBy(operatorId)
                    .build());
        } else {
            // 幂等：已封禁再封只更新原因与操作人，不重复插行
            guardMapper.updateById(AiGuard.builder()
                    .userId(userId)
                    .banned(1)
                    .reason(text == null ? DEFAULT_BAN_REASON : text)
                    .updateBy(operatorId)
                    .build());
        }
        log.info("[AI 护栏] 封禁 userId={}, operator={}, reason={}", userId, operatorId, text);
    }

    @Override
    public void unban(Long userId, Long operatorId) {
        if (userId == null) {
            return;
        }
        AiGuard guard = guardMapper.selectById(userId);
        if (guard == null || !Integer.valueOf(1).equals(guard.getBanned())) {
            // 没封过：幂等直返，不插一行 banned=0 去污染「查不到即未封」的最短路径
            return;
        }
        guardMapper.updateById(AiGuard.builder()
                .userId(userId)
                .banned(0)
                .updateBy(operatorId)
                .build());
        log.info("[AI 护栏] 解封 userId={}, operator={}", userId, operatorId);
    }

    /* ==================== 内部装配 ==================== */

    /** 烧钱榜：GROUP BY user_id 按累计扣预算耗时倒序取前几名 */
    private List<AdminAiOverviewDTO.TopUser> topUsers() {
        List<Map<String, Object>> rows = usageMapper.selectMaps(Wrappers.<AiUsage>query()
                .select("user_id",
                        "COALESCE(SUM(CASE WHEN success = 1 THEN duration_ms ELSE 0 END), 0) AS total_ms",
                        "COUNT(*) AS calls")
                .groupBy("user_id")
                .orderByDesc("total_ms")
                .last("LIMIT " + TOP_USERS));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            userIds.add(asLong(row.get("user_id")));
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
        List<AdminAiOverviewDTO.TopUser> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long userId = asLong(row.get("user_id"));
            result.add(AdminAiOverviewDTO.TopUser.builder()
                    .userId(userId)
                    .userName(displayOf(briefs.get(userId)))
                    .totalMs(asLong(row.get("total_ms")))
                    .calls(asLong(row.get("calls")))
                    .build());
        }
        return result;
    }

    private List<Long> bannedUserIds() {
        return guardMapper.selectList(Wrappers.<AiGuard>lambdaQuery()
                        .select(AiGuard::getUserId).eq(AiGuard::getBanned, 1))
                .stream().map(AiGuard::getUserId).toList();
    }

    private Map<Long, AiGuard> guardMap(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return guardMapper.selectList(Wrappers.<AiGuard>lambdaQuery().in(AiGuard::getUserId, userIds))
                .stream().collect(java.util.stream.Collectors.toMap(AiGuard::getUserId, g -> g));
    }

    /** 昵称优先，昵称空着退到账号，查不到这个人就是「已注销用户」 */
    private static String displayOf(UserBriefDTO user) {
        if (user == null) {
            return DELETED_NICKNAME;
        }
        return TextUtil.isBlank(user.getNickname()) ? user.getUsername() : user.getNickname();
    }

    private static Map<String, Object> firstMap(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** MySQL 的 SUM 回来是 BigDecimal，COUNT 与 BIGINT 列是 Long，统一收成 long */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
