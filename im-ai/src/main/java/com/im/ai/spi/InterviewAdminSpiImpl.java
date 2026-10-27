package com.im.ai.spi;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.ai.entity.InterviewEvent;
import com.im.ai.entity.InterviewMessage;
import com.im.ai.entity.InterviewSession;
import com.im.ai.mapper.InterviewEventMapper;
import com.im.ai.mapper.InterviewMessageMapper;
import com.im.ai.mapper.InterviewSessionMapper;
import com.im.common.api.PageResult;
import com.im.common.domain.AdminInterviewOverviewDTO;
import com.im.common.domain.AdminInterviewQueryDTO;
import com.im.common.domain.AdminInterviewSessionDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.spi.AdminInterviewSpi;
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
 * 后台 AI 面试记录契约的实现（{@link AdminInterviewSpi}），读 {@code im_interview_session} 及其两张明细表。
 *
 * <p>这个类<b>一个写操作都没有</b>，连「把僵尸会话标成未完成」这种看起来纯属打扫的动作都不做，
 * 理由写在接口注释里：那些计数是判罚依据，后台一改，这场面试的结论就再也拿不出手。
 * 唯一的副作用是一行 INFO 日志——管理员点开某场面试的作答原文时留下痕迹。
 *
 * <p>概览里的数字全部来自<b>一条</b>带 {@code CASE} 的聚合：{@code im_interview_session}
 * 除两条索引外就是全表，跑八遍和跑一遍都是全表扫，分开查等于把同一趟扫描的代价付八次。
 * 时间条件用 {@code CURDATE()} 与 {@code DATE_SUB(NOW(), INTERVAL ... SECOND)} 而不是把
 * Java 侧的 {@code LocalDateTime} 拼进 SQL：一是省掉八次参数绑定，二是「今天」与「多久算僵尸」
 * 只该有一个权威时钟，那就是数据库那一个——应用与库不在同一时区时，
 * 传进来的时间戳会让「今日场次」在每天的头几个小时里是错的。
 *
 * <p>拼进 {@code select()} 的字符串全是本类私有常量，唯一的外部数值 {@code STALE_SECONDS}
 * 是编译期 int，不存在注入面。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAdminSpiImpl implements AdminInterviewSpi {

    /** 单页上限，与直播 / 视频 / 远程控制后台同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    private static final int KEYWORD_MAX = 50;
    /** 违规榜取前几名，见 {@code AdminInterviewOverviewDTO#topViolators} 的字段注释 */
    private static final int TOP_VIOLATORS = 10;
    /** 单场事件流水的上限，与候选人侧 {@code InterviewAuditService#detail} 同值：一次批量上报能塞 100 条 */
    private static final int MAX_EVENTS = 500;
    /** 「进行中却已失联」的判定线（秒）：两小时没有任何一场合理面试还挂着，见接口注释 */
    private static final int STALE_SECONDS = 2 * 60 * 60;
    private static final String DELETED_NICKNAME = "已注销用户";

    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final InterviewEventMapper eventMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public PageResult<AdminInterviewSessionDTO> pageSessions(AdminInterviewQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);

        // 函数排序表达式 Lambda 表达不了，所以这里用 QueryWrapper 字符串列（同 LiveAdminSpiImpl）
        QueryWrapper<InterviewSession> wrapper = new QueryWrapper<InterviewSession>()
                .eq(query.getUserId() != null, "user_id", query.getUserId())
                .eq(query.getStatus() != null, "status", query.getStatus())
                .ge(query.getStartTime() != null, "start_time", query.getStartTime())
                .le(query.getEndTime() != null, "start_time", query.getEndTime())
                .like(keyword != null, "title", keyword);
        // minViolations 优先：勾了「只看有违规」又填了下限，那下限才是他真正想筛的东西
        Integer min = query.getMinViolations();
        if (min != null && min > 0) {
            wrapper.ge("violation_count", min);
        } else if (Boolean.TRUE.equals(query.getViolationOnly())) {
            wrapper.gt("violation_count", 0);
        }
        wrapper.orderByAsc("FIELD(status, 0, 1, 2, 3)").orderByDesc("create_time").orderByDesc("id");

        Page<InterviewSession> page = sessionMapper.selectPage(new Page<>(current, size), wrapper);
        List<InterviewSession> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Set<Long> userIds = new HashSet<>();
        for (InterviewSession row : rows) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
        List<AdminInterviewSessionDTO> records = new ArrayList<>(rows.size());
        for (InterviewSession row : rows) {
            // 列表行不带 qaList / events / violationByType，那三样只在档案里给（体积原因见 DTO 类注释）
            records.add(toView(row, displayOf(briefs.get(row.getUserId())), null, null, null));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminInterviewSessionDTO detail(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return null;
        }
        UserBriefDTO candidate = session.getUserId() == null
                ? null : userQuerySpi.getById(session.getUserId());
        // 这一句是本类唯一的「读候选人作答原文」的入口留痕：这一屏单独设权限点的目的就是它
        log.info("[后台面试档案] sessionId={}, userId={}, status={}, violations={}",
                sessionId, session.getUserId(), session.getStatus(), session.getViolationCount());
        return toView(session, displayOf(candidate),
                eventMapper.countViolationByType(sessionId),
                qaList(sessionId),
                eventList(sessionId));
    }

    @Override
    public AdminInterviewOverviewDTO overview() {
        Map<String, Object> row = firstMap(sessionMapper.selectMaps(Wrappers.<InterviewSession>query()
                .select("COUNT(*) AS totalSessions",
                        "COALESCE(SUM(CASE WHEN create_time >= CURDATE() THEN 1 ELSE 0 END), 0) AS todaySessions",
                        "COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0) AS ongoingSessions",
                        "COALESCE(SUM(CASE WHEN status = 0 AND start_time IS NOT NULL"
                                + " AND start_time < DATE_SUB(NOW(), INTERVAL " + STALE_SECONDS + " SECOND)"
                                + " THEN 1 ELSE 0 END), 0) AS staleOngoing",
                        "COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0) AS violationLimitSessions",
                        "COALESCE(SUM(CASE WHEN status = 2 AND create_time >= CURDATE() THEN 1 ELSE 0 END), 0)"
                                + " AS todayViolationLimit",
                        "COALESCE(SUM(CASE WHEN status IN (1, 2, 3) THEN 1 ELSE 0 END), 0) AS endedSessions",
                        "COALESCE(SUM(CASE WHEN status IN (1, 2, 3) AND violation_count > 0"
                                + " THEN 1 ELSE 0 END), 0) AS violatedSessions")));
        long ended = asLong(row.get("endedSessions"));
        long violated = asLong(row.get("violatedSessions"));

        List<AdminInterviewOverviewDTO.TopViolator> top = topViolators();
        return AdminInterviewOverviewDTO.builder()
                .totalSessions(asLong(row.get("totalSessions")))
                .todaySessions(asLong(row.get("todaySessions")))
                .ongoingSessions(asLong(row.get("ongoingSessions")))
                .staleOngoing(asLong(row.get("staleOngoing")))
                .violationLimitSessions(asLong(row.get("violationLimitSessions")))
                .todayViolationLimit(asLong(row.get("todayViolationLimit")))
                // 分母只算已结束的：把刚开考的算进去，这个比率会在每个面试高峰的整点虚假跳水
                .violationRate(ended <= 0 ? 0d : (double) violated / (double) ended)
                .topViolators(top)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 按候选人聚合的违规榜，全表 {@code GROUP BY user_id}。
     *
     * <p>只用于「该找谁聊聊」，不构成处罚依据——它数的是会话表上的展示缓存之和，
     * 而定性依据是事件流水（{@code InterviewSession} 的类注释把这条界线写得很清楚）。
     */
    private List<AdminInterviewOverviewDTO.TopViolator> topViolators() {
        List<Map<String, Object>> rows = sessionMapper.selectMaps(Wrappers.<InterviewSession>query()
                .select("user_id", "SUM(violation_count) AS violations", "COUNT(*) AS sessions")
                .groupBy("user_id")
                .having("SUM(violation_count) > 0")
                .orderByDesc("violations")
                .last("LIMIT " + TOP_VIOLATORS));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        List<Long> ordered = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            long userId = asLong(row.get("user_id"));
            ordered.add(userId);
            userIds.add(userId);
        }
        Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
        List<AdminInterviewOverviewDTO.TopViolator> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            long userId = ordered.get(i);
            result.add(AdminInterviewOverviewDTO.TopViolator.builder()
                    .userId(userId)
                    .candidateName(displayOf(briefs.get(userId)))
                    .violations(asLong(row.get("violations")))
                    .sessions(asLong(row.get("sessions")))
                    .build());
        }
        return result;
    }

    private List<AdminInterviewSessionDTO.QaItem> qaList(Long sessionId) {
        List<InterviewMessage> messages = messageMapper.selectList(Wrappers.<InterviewMessage>lambdaQuery()
                .eq(InterviewMessage::getSessionId, sessionId)
                // 排序以 seq 为准：一轮的两行常落在同一毫秒，按时间排会把提问和作答排反
                .orderByAsc(InterviewMessage::getSeq));
        List<AdminInterviewSessionDTO.QaItem> result = new ArrayList<>(messages.size());
        for (InterviewMessage message : messages) {
            result.add(AdminInterviewSessionDTO.QaItem.builder()
                    .seq(message.getSeq())
                    .turnNo(message.getTurnNo())
                    .role(message.getRole())
                    .content(message.getContent())
                    .charCount(message.getCharCount())
                    .elapsedMs(message.getElapsedMs())
                    .createTime(message.getCreateTime())
                    .build());
        }
        return result;
    }

    private List<AdminInterviewSessionDTO.EventItem> eventList(Long sessionId) {
        List<InterviewEvent> events = eventMapper.selectList(Wrappers.<InterviewEvent>lambdaQuery()
                .eq(InterviewEvent::getSessionId, sessionId)
                .orderByAsc(InterviewEvent::getEventTime)
                .orderByAsc(InterviewEvent::getId)
                .last("LIMIT " + MAX_EVENTS));
        List<AdminInterviewSessionDTO.EventItem> result = new ArrayList<>(events.size());
        for (InterviewEvent event : events) {
            result.add(AdminInterviewSessionDTO.EventItem.builder()
                    .eventId(event.getId())
                    .eventType(event.getEventType())
                    .violation(event.getViolation())
                    .turnNo(event.getTurnNo())
                    .detail(event.getDetail())
                    .eventTime(event.getEventTime())
                    .createTime(event.getCreateTime())
                    .build());
        }
        return result;
    }

    /** 会话行装配成 DTO：三个明细参数为 null 即列表行 */
    private AdminInterviewSessionDTO toView(InterviewSession session, String candidateName,
                                            Map<String, Long> violationByType,
                                            List<AdminInterviewSessionDTO.QaItem> qaList,
                                            List<AdminInterviewSessionDTO.EventItem> events) {
        return AdminInterviewSessionDTO.builder()
                .sessionId(session.getId())
                .userId(session.getUserId())
                .candidateName(candidateName)
                .title(session.getTitle())
                .status(session.getStatus())
                .startTime(session.getStartTime())
                .endTime(session.getEndTime())
                .durationSeconds(session.getDurationSeconds())
                .turnCount(session.getTurnCount())
                .violationCount(session.getViolationCount())
                .blurCount(session.getBlurCount())
                .copyCount(session.getCopyCount())
                .pasteCount(session.getPasteCount())
                .fullscreenExitCount(session.getFullscreenExitCount())
                .endReason(session.getEndReason())
                .screen(session.getScreen())
                .clientInfo(session.getClientInfo())
                .createTime(session.getCreateTime())
                .violationByType(violationByType)
                .qaList(qaList)
                .events(events)
                .build();
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
