package com.im.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.ai.config.ImAiProperties;
import com.im.ai.dto.req.InterviewEndRequest;
import com.im.ai.dto.req.InterviewEventReportRequest;
import com.im.ai.dto.req.InterviewTurnRequest;
import com.im.ai.entity.InterviewEvent;
import com.im.ai.entity.InterviewMessage;
import com.im.ai.entity.InterviewSession;
import com.im.ai.mapper.InterviewEventMapper;
import com.im.ai.mapper.InterviewMessageMapper;
import com.im.ai.mapper.InterviewSessionMapper;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 面试监考与审计服务：会话开始、事件上报、问答落库、结束收尾与事后查询。
 *
 * <p>与 {@link InterviewService} 分工清楚：那边只管跟模型对话，不碰数据库；
 * 这边只管记录，一次模型都不调。分开的原因是两者的失败模式完全不同——
 * 模型超时不该让审计记录写不进去，反之审计写失败也不该打断正在进行的面试
 * （所以上报接口出错时前端只记 console，不弹给用户）。
 *
 * <h2>信任边界</h2>
 *
 * <p>前端只上报「发生了什么」（切屏了、粘贴了），<b>不判定这算不算违规</b>：
 * violation 标志、计入哪个计数、是否达到阈值全部在服务端按 {@link ImAiProperties.Proctor}
 * 算。否则改一下浏览器脚本就能把粘贴上报成非违规，审计直接失去意义。
 *
 * <p>同理，sessionId 必须校验归属：拿到别人的 sessionId 往上写事件，
 * 等于把违规记到无辜者头上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewAuditService {

    private final ImAiProperties aiProperties;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final InterviewEventMapper eventMapper;

    /**
     * 事件类型白名单。不在名单里的一律丢弃：前端版本比后端新（加了个新事件类型）时，
     * 宁可不记也不能把任意字符串写进列，也不能让整个上报请求失败。
     */
    private static final Set<String> KNOWN_TYPES = Set.of(
            InterviewEvent.TYPE_VISIBILITY_HIDDEN,
            InterviewEvent.TYPE_BLUR,
            InterviewEvent.TYPE_COPY,
            InterviewEvent.TYPE_CUT,
            InterviewEvent.TYPE_PASTE,
            InterviewEvent.TYPE_CONTEXT_MENU,
            InterviewEvent.TYPE_FULLSCREEN_EXIT,
            InterviewEvent.TYPE_SESSION_START,
            InterviewEvent.TYPE_TURN_SUBMIT,
            InterviewEvent.TYPE_SESSION_END);

    /** 流程事件：只留痕，永不计入违规 */
    private static final Set<String> FLOW_TYPES = Set.of(
            InterviewEvent.TYPE_SESSION_START,
            InterviewEvent.TYPE_TURN_SUBMIT,
            InterviewEvent.TYPE_SESSION_END);

    /* ==================== 会话生命周期 ==================== */

    /**
     * 开始一场面试：先把该用户历史上没收尾的会话补记为「未完成」，再建新的。
     *
     * <p>补记不是洁癖——刷新、断网、直接关标签页都不会走到结束接口，
     * 库里会留下永久 status=0 的行。放在开始时做而不是加定时任务：
     * 一个候选人不会同时开两场面试，所以「开始时清一次」恰好覆盖全部残留，
     * 代价只是一条 UPDATE。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long userId, String title, String screen) {
        ImAiProperties.Proctor proctor = aiProperties.getProctor();
        LocalDateTime now = LocalDateTime.now();

        InterviewSession abandoned = InterviewSession.builder()
                .status(InterviewSession.STATUS_INCOMPLETE)
                .endTime(now)
                .endReason(InterviewSession.REASON_CLOSE)
                .build();
        int closed = sessionMapper.update(abandoned, Wrappers.<InterviewSession>update()
                .eq("user_id", userId)
                .eq("status", InterviewSession.STATUS_ONGOING));
        if (closed > 0) {
            log.info("面试会话未正常收尾，已补记为未完成: userId={}, count={}", userId, closed);
        }

        InterviewSession session = InterviewSession.builder()
                .userId(userId)
                .title(trimTo(title, 64, "后端 Java 全栈面试"))
                .status(InterviewSession.STATUS_ONGOING)
                .startTime(now)
                .durationSeconds(0)
                .turnCount(0)
                .violationCount(0)
                .blurCount(0)
                .copyCount(0)
                .pasteCount(0)
                .fullscreenExitCount(0)
                .screen(trimTo(screen, 32, null))
                .clientInfo(clientSummary())
                .build();
        sessionMapper.insert(session);

        if (proctor.isEnabled()) {
            insertEvent(session.getId(), userId, InterviewEvent.TYPE_SESSION_START, false, 0, null, now);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("proctorEnabled", proctor.isEnabled());
        result.put("violationLimit", proctor.getViolationLimit());
        result.put("enforceLimit", proctor.isEnforceLimit());
        return result;
    }

    /**
     * 接收一批监考事件：逐条落流水，按类型原子累加会话计数，最后判断是否触顶。
     *
     * @return 最新计数与是否已被强制结束，前端据此弹警告或直接终止
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> report(Long userId, InterviewEventReportRequest request) {
        ImAiProperties.Proctor proctor = aiProperties.getProctor();
        InterviewSession session = requireOngoing(request.getSessionId(), userId);
        LocalDateTime now = LocalDateTime.now();

        int violationDelta = 0;
        int blurDelta = 0;
        int copyDelta = 0;
        int pasteDelta = 0;
        int fullscreenDelta = 0;

        List<InterviewEventReportRequest.EventItem> items = request.getEvents();
        int cap = Math.max(proctor.getMaxEventsPerBatch(), 1);
        if (items.size() > cap) {
            // 截断而不是报错：一次超限多半是前端攒了太久（离线补传），
            // 拒收整批会让这些记录彻底丢掉，收下前 cap 条至少留下大部分线索
            log.warn("面试事件单批上报超限，截断: sessionId={}, size={}, cap={}",
                    session.getId(), items.size(), cap);
            items = items.subList(0, cap);
        }

        for (InterviewEventReportRequest.EventItem item : items) {
            String type = item.getEventType();
            if (!KNOWN_TYPES.contains(type)) {
                log.debug("忽略未知面试事件类型: {}", type);
                continue;
            }
            int turnNo = item.getTurnNo() == null ? 0 : Math.max(item.getTurnNo(), 0);
            boolean violation = countsAsViolation(type, proctor);
            insertEvent(session.getId(), userId, type, violation, turnNo,
                    trimTo(item.getDetail(), proctor.getMaxDetailChars(), null),
                    item.getEventTime() == null ? now : item.getEventTime());
            if (!violation) {
                continue;
            }
            violationDelta++;
            switch (type) {
                case InterviewEvent.TYPE_VISIBILITY_HIDDEN, InterviewEvent.TYPE_BLUR -> blurDelta++;
                case InterviewEvent.TYPE_COPY -> copyDelta++;
                case InterviewEvent.TYPE_PASTE -> pasteDelta++;
                case InterviewEvent.TYPE_FULLSCREEN_EXIT -> fullscreenDelta++;
                // cut / contextmenu 没有单独列，只进 violation_count 总数：
                // 单独加列要为一年可能用上一次的字段付全表成本，不值
                default -> { }
            }
        }

        sessionMapper.incrementCounters(session.getId(), 0, violationDelta,
                blurDelta, copyDelta, pasteDelta, fullscreenDelta);

        InterviewSession latest = sessionMapper.selectById(session.getId());
        int total = latest == null || latest.getViolationCount() == null ? 0 : latest.getViolationCount();
        int limit = proctor.getViolationLimit();
        boolean reached = proctor.isEnabled() && limit > 0 && total >= limit;
        boolean ended = false;
        if (reached && proctor.isEnforceLimit()) {
            finish(latest, InterviewSession.STATUS_VIOLATION_LIMIT, InterviewSession.REASON_VIOLATION_LIMIT, now);
            ended = true;
            log.info("面试违规达到上限被强制结束: sessionId={}, userId={}, violations={}, limit={}",
                    session.getId(), userId, total, limit);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("violationCount", total);
        result.put("blurCount", latest == null ? 0 : latest.getBlurCount());
        result.put("copyCount", latest == null ? 0 : latest.getCopyCount());
        result.put("pasteCount", latest == null ? 0 : latest.getPasteCount());
        result.put("fullscreenExitCount", latest == null ? 0 : latest.getFullscreenExitCount());
        result.put("limit", limit);
        result.put("reachedLimit", reached);
        result.put("ended", ended);
        return result;
    }

    /**
     * 落一轮问答：一次上报同时带「候选人刚才的作答」与「面试官紧接着出的新题」。
     *
     * <p>先写作答再写提问，seq 才与真实对话一致：一问一答是由候选人那次提交分隔开的，
     * 前端拿到新提问时手里正好攒着上一题的答案，两个一起送就是一轮完整的边界。
     * 反过来先写提问会得到 Q1、Q2、A1 这种读不通的序列。
     *
     * <p>{@code turn_count} 只在有提问时加一（一轮 = 面试官出的一道题），
     * 开场的第一个问题与结尾的总结都只有提问没有作答。
     *
     * <p>重复上报（前端重试）撞上 {@code uk_session_turn} 时静默跳过，并且不再
     * 加 turn_count、也不再补一条 turn-submit 事件——这一轮已经完整地在那儿了，
     * 再抛异常只会让前端以为没存上而反复重传。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appendTurn(Long userId, InterviewTurnRequest request) {
        InterviewSession session = requireOngoing(request.getSessionId(), userId);
        String question = trimToBlank(request.getQuestion());
        String answer = trimToBlank(request.getAnswer());
        if (question.isEmpty() && answer.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "提问与作答不能都为空");
        }
        int limit = aiProperties.getMaxMessageChars();
        LocalDateTime now = LocalDateTime.now();

        int current = session.getTurnCount() == null ? 0 : session.getTurnCount();
        // 题号以它为准才能拦住重试：seq 是服务端 maxSeq+1 现算的，重传一次就是个新值。
        // 前端没带（老前端或手工调用）时按当前轮数推算，此时不保证幂等。
        int questionTurn = request.getTurnNo() == null || request.getTurnNo() < 1
                ? current + 1 : request.getTurnNo();
        int answerTurn = Math.max(questionTurn - 1, 0);

        int seq = messageMapper.maxSeq(session.getId());
        boolean answerInserted = false;
        if (!answer.isEmpty()) {
            long elapsed = request.getElapsedMs() == null || request.getElapsedMs() < 0 ? 0L : request.getElapsedMs();
            answerInserted = insertMessage(session.getId(), seq + 1, answerTurn,
                    InterviewMessage.ROLE_USER, truncate(answer, limit), elapsed);
            if (answerInserted) {
                seq = seq + 1;
            }
        }
        boolean questionInserted = false;
        if (!question.isEmpty()) {
            questionInserted = insertMessage(session.getId(), seq + 1, questionTurn,
                    InterviewMessage.ROLE_ASSISTANT, truncate(question, limit), 0L);
            if (questionInserted) {
                seq = seq + 1;
                sessionMapper.incrementCounters(session.getId(), 1, 0, 0, 0, 0, 0);
            }
        }
        if (answerInserted || questionInserted) {
            insertEvent(session.getId(), userId, InterviewEvent.TYPE_TURN_SUBMIT, false, questionTurn,
                    "answerChars=" + answer.length(), now);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seq", seq);
        result.put("turnNo", questionTurn);
        result.put("duplicate", !answerInserted && !questionInserted);
        return result;
    }

    /**
     * 结束面试。幂等：已经结束的直接回当前汇总，不报错也不覆盖既有结束原因，
     * 因为「违规强制结束」和「用户自己点结束」谁先落地，审计结论就应当保留谁。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> end(Long userId, InterviewEndRequest request) {
        InterviewSession session = requireOwned(request.getSessionId(), userId);
        if (session.getStatus() != null && session.getStatus() != InterviewSession.STATUS_ONGOING) {
            return summaryOf(session);
        }
        String reason = request.getReason() == null || request.getReason().isBlank()
                ? InterviewSession.REASON_USER_END : request.getReason();
        int status = switch (reason) {
            case InterviewSession.REASON_VIOLATION_LIMIT -> InterviewSession.STATUS_VIOLATION_LIMIT;
            case InterviewSession.REASON_USER_END -> InterviewSession.STATUS_ENDED;
            default -> InterviewSession.STATUS_INCOMPLETE;
        };
        finish(session, status, reason, LocalDateTime.now());
        return summaryOf(sessionMapper.selectById(session.getId()));
    }

    /* ==================== 事后查询 ==================== */

    /** 我的面试记录分页，倒序 */
    public Page<InterviewSession> pageMine(Long userId, long current, long size, Integer status) {
        return sessionMapper.selectPage(new Page<>(current, size),
                Wrappers.<InterviewSession>query()
                        .eq("user_id", userId)
                        .eq(status != null, "status", status)
                        .orderByDesc("create_time"));
    }

    /**
     * 单场面试的完整复盘：会话汇总 + 违规构成 + 逐轮问答 + 事件时间线。
     *
     * <p>事件一次性带回（上限 500）而不分页：审计页要把时间线画在一张图上，
     * 分页会让「第 3 题时切屏」这种对应关系看不出来；真超 500 条的会话
     * 本身就已经是结论了。
     */
    public Map<String, Object> detail(Long userId, Long sessionId) {
        InterviewSession session = requireOwned(sessionId, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", session);
        result.put("violationByType", eventMapper.countViolationByType(sessionId));
        result.put("messages", messageMapper.selectList(Wrappers.<InterviewMessage>query()
                .eq("session_id", sessionId)
                .orderByAsc("seq")));
        result.put("events", eventMapper.selectList(Wrappers.<InterviewEvent>query()
                .eq("session_id", sessionId)
                .orderByAsc("event_time", "id")
                .last("LIMIT 500")));
        return result;
    }

    /** 监考配置的前端视图：决定要不要挂监听、弹几次警告、多久 flush */
    public Map<String, Object> proctorView() {
        ImAiProperties.Proctor proctor = aiProperties.getProctor();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", proctor.isEnabled());
        view.put("violationLimit", proctor.getViolationLimit());
        view.put("enforceLimit", proctor.isEnforceLimit());
        // 逐项告知「哪类动作会被记为违规」：前端据此决定要不要监听它，
        // 不监也不上报，比监了再让服务端丢弃省流量，也不会让用户看到无意义的警告
        view.put("watchBlur", proctor.isCountBlur());
        view.put("watchVisibilityHidden", proctor.isCountVisibilityHidden());
        view.put("watchCopy", proctor.isCountCopy());
        view.put("watchCut", proctor.isCountCut());
        view.put("watchPaste", proctor.isCountPaste());
        view.put("watchContextmenu", proctor.isCountContextmenu());
        view.put("watchFullscreenExit", proctor.isCountFullscreenExit());
        return view;
    }

    /* ==================== 内部实现 ==================== */

    /** 写库收尾：状态、结束时间、结束原因与时长一次更新 */
    private void finish(InterviewSession session, int status, String reason, LocalDateTime endTime) {
        LocalDateTime startTime = session.getStartTime() == null ? endTime : session.getStartTime();
        int duration = (int) Math.max(Duration.between(startTime, endTime).getSeconds(), 0);
        InterviewSession update = InterviewSession.builder()
                .id(session.getId())
                .status(status)
                .endTime(endTime)
                .endReason(reason)
                .durationSeconds(duration)
                .build();
        sessionMapper.updateById(update);
        insertEvent(session.getId(), session.getUserId(), InterviewEvent.TYPE_SESSION_END, false,
                session.getTurnCount() == null ? 0 : session.getTurnCount(), reason, endTime);
    }

    /**
     * 写一行问答。
     *
     * @return true 确实插入了；false 撞 {@code uk_session_turn}（同一轮同一角色重传）已忽略
     */
    private boolean insertMessage(Long sessionId, int seq, int turnNo, String role, String content, long elapsedMs) {
        InterviewMessage message = InterviewMessage.builder()
                .sessionId(sessionId)
                .seq(seq)
                .turnNo(turnNo)
                .role(role)
                .content(content)
                .charCount(content.length())
                .elapsedMs(elapsedMs)
                .build();
        try {
            messageMapper.insert(message);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("面试问答重复上报已忽略: sessionId={}, turnNo={}, role={}", sessionId, turnNo, role);
            return false;
        }
    }

    private void insertEvent(Long sessionId, Long userId, String type, boolean violation,
                             int turnNo, String detail, LocalDateTime eventTime) {
        InterviewEvent event = InterviewEvent.builder()
                .sessionId(sessionId)
                .userId(userId)
                .eventType(type)
                .violation(violation ? 1 : 0)
                .turnNo(turnNo)
                .detail(detail)
                .eventTime(eventTime)
                .build();
        eventMapper.insert(event);
    }

    /** 违规判定只在这里发生，全部依据配置，不接受前端传来的标志 */
    private boolean countsAsViolation(String type, ImAiProperties.Proctor proctor) {
        if (FLOW_TYPES.contains(type)) {
            return false;
        }
        return switch (type) {
            case InterviewEvent.TYPE_VISIBILITY_HIDDEN -> proctor.isCountVisibilityHidden();
            case InterviewEvent.TYPE_BLUR -> proctor.isCountBlur();
            case InterviewEvent.TYPE_COPY -> proctor.isCountCopy();
            case InterviewEvent.TYPE_CUT -> proctor.isCountCut();
            case InterviewEvent.TYPE_PASTE -> proctor.isCountPaste();
            case InterviewEvent.TYPE_CONTEXT_MENU -> proctor.isCountContextmenu();
            case InterviewEvent.TYPE_FULLSCREEN_EXIT -> proctor.isCountFullscreenExit();
            default -> false;
        };
    }

    private InterviewSession requireOngoing(Long sessionId, Long userId) {
        InterviewSession session = requireOwned(sessionId, userId);
        if (session.getStatus() != null && session.getStatus() != InterviewSession.STATUS_ONGOING) {
            throw new BusinessException(ResultCode.AI_INTERVIEW_ENDED);
        }
        return session;
    }

    private InterviewSession requireOwned(Long sessionId, Long userId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.AI_INTERVIEW_NOT_FOUND);
        }
        if (!session.getUserId().equals(userId)) {
            // 单独报「无权访问」而不是「不存在」：会话 ID 是雪花值，
            // 猜不到别人的，所以能命中就是真的越权，要在日志里留痕
            log.warn("越权访问面试会话: sessionId={}, owner={}, current={}", sessionId, session.getUserId(), userId);
            throw new BusinessException(ResultCode.AI_INTERVIEW_FORBIDDEN);
        }
        return session;
    }

    private Map<String, Object> summaryOf(InterviewSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("status", session.getStatus());
        result.put("endReason", session.getEndReason());
        result.put("durationSeconds", session.getDurationSeconds());
        result.put("turnCount", session.getTurnCount());
        result.put("violationCount", session.getViolationCount());
        result.put("blurCount", session.getBlurCount());
        result.put("copyCount", session.getCopyCount());
        result.put("pasteCount", session.getPasteCount());
        result.put("fullscreenExitCount", session.getFullscreenExitCount());
        return result;
    }

    /**
     * 客户端环境摘要：只留引擎与版本，不落完整 UA。
     *
     * <p>完整 UA 长且信息过剩（机型、build 号），排障时真正要看的就两样：
     * 什么系统、什么浏览器。另外 UA 属于可识别设备的个人信息，能少存就少存。
     */
    private String clientSummary() {
        String ua = SecurityUtil.getRequest() == null ? null : SecurityUtil.getRequest().getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>(2);
        int start = ua.indexOf('(');
        int end = ua.indexOf(')');
        if (start > 0 && end > start) {
            String platform = ua.substring(start + 1, end).split(";")[0].trim();
            if (!platform.isEmpty()) {
                parts.add(platform);
            }
        }
        // 顺序有讲究：Edg 与 Chrome 会同时出现在 Edge 的 UA 里，先命中的是伪装的那一个
        for (String marker : new String[]{"Edg/", "OPR/", "Firefox/", "Chrome/", "Safari/"}) {
            int index = ua.indexOf(marker);
            if (index >= 0) {
                int tail = ua.indexOf(' ', index);
                parts.add(ua.substring(index, tail < 0 ? ua.length() : tail));
                break;
            }
        }
        return parts.isEmpty() ? null : truncate(String.join(" ", parts), 255);
    }

    private static String trimTo(String value, int max, String fallback) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return fallback;
        }
        return truncate(trimmed, max);
    }

    private static String trimToBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
