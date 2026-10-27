package com.im.message.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminMessageDTO;
import com.im.common.domain.AdminMessageOverviewDTO;
import com.im.common.domain.AdminMessageQueryDTO;
import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ConvType;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminMessageSpi;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.message.convert.MessageConvert;
import com.im.message.entity.Message;
import com.im.message.entity.MessageRead;
import com.im.message.mapper.MessageMapper;
import com.im.message.mapper.MessageReadMapper;
import com.im.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台消息管理契约的实现（{@link AdminMessageSpi}）。
 *
 * <p>这一屏的全部危险都集中在一个词上：<b>读别人的聊天正文</b>。所以实现里能收的口子都收：
 * 带 {@code keyword} 就必须带范围（{@link #pageMessages}）、单页 50 行、正文截到 2000 字、
 * {@code extra} 只给可读摘要而<b>绝不给 {@code fileUrl}</b>（附件本体要走文件那一屏的鉴权）。
 * 权限点 {@code system:message:manage} 挂在 Controller 上，与 {@code system:log} 同级对待。
 *
 * <h2>与用户侧视图的一处关键差别</h2>
 *
 * <p>{@code MessageConvert} 在消息已撤回时一律把 {@code content} 与 {@code extra} 抹掉，
 * 这是对的——对当事人来说撤回就是「从此不再可见」。但后台不是当事人：管理员来这一屏
 * 查的往往正是「他发过什么、后来撤了什么」，所以这里的已撤回行<b>照常给出原文与 {@code recallTime}</b>。
 * 代价是这个视图<b>永远不能下发给任何客户端</b>：它只能进后台，
 * 这也是它单独走 {@code AdminMessageDTO} 而不是复用 {@code MessageVO} 的原因。
 *
 * <h2>回填昵称与会话名的取数形状</h2>
 *
 * <p>一页最多 50 行，去重后的会话数只会更少，所以会话信息按「每个会话一次主键点查」取，
 * 与本模块 {@code MessageServiceImpl#conversationTypesOf} 已有的做法同构。
 * 单聊的展示名在这里必须自己拼：{@code ConversationSpi#brief} 的 {@code viewerId} 为空时
 * 拿不到「对方」（那个概念本身就是相对查看者的），于是单聊会话名取两位成员昵称拼成
 * 「A ↔ B」——对管理员而言这才是那条私聊的身份，比 {@code 单聊 #123} 有用得多。
 * 成员 ID 顺手并进发送者那一次 {@code listByIds}，整页仍然只有一次用户查询。
 *
 * <h2>后台强撤的痕迹从哪来</h2>
 *
 * <p>{@code overview()} 里的 {@code todayAdminRecalled} 数的是 {@code extra} JSON 列上的
 * {@code recalledByAdmin} 键，由 {@code MessageServiceImpl#recallInternal} 的 force 分支
 * 与 {@code markRecalled} 同事务写入。这一批<b>零 DDL</b>，所以问责标记只能落在 JSON 里；
 * 该键上线以前的历史行为缺失，读作「不是后台撤的」，不做回溯猜测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageAdminSpiImpl implements AdminMessageSpi {

    /** 单页上限：一行要带正文摘要、会话名与两个昵称，比别的屏更贵，收到 50 */
    private static final long MAX_PAGE_SIZE = 50;
    private static final int KEYWORD_MAX = 30;
    /** 列表页正文截断阈值，全文要用 {@link #detail} 单独取 */
    private static final int CONTENT_MAX = 2000;
    /** {@code extra} 摘要的截断阈值：接龙条目、合并转发快照都可能很长 */
    private static final int SUMMARY_MAX = 200;
    /** 概览里「近 7 日」的跨度，减 6 天再加上今天正好 7 天 */
    private static final int WEEK_SPAN_DAYS = 6;
    private static final String DELETED_NICKNAME = "已注销用户";
    private static final String SYSTEM_NAME = "系统";
    /** 会话名兜底：会话行查不到时至少让管理员知道这是哪个会话 */
    private static final String CONV_FALLBACK = "会话 #";

    private final MessageMapper messageMapper;
    private final MessageReadMapper messageReadMapper;
    private final MessageService messageService;
    private final ConversationSpi conversationSpi;
    private final UserQuerySpi userQuerySpi;
    private final ImProperties imProperties;

    @Override
    public PageResult<AdminMessageDTO> pageMessages(AdminMessageQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);
        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);

        // 「有 keyword 就必须有范围」这条硬约束落在实现方而不是界面提示里：界面会被绕过，curl 不会读注释。
        // 判定用的是范围条件「存在」而不是「有效」，所以 startTime 单独给出也算合格——
        // 那已经是「只扫今天」这种明确意图，不需要再逼管理员补一个 endTime。
        if (keyword != null) {
            boolean scoped = query.getConversationId() != null || query.getFromUserId() != null
                    || query.getStartTime() != null || query.getEndTime() != null;
            BusinessException.throwIf(!scoped, ResultCode.BAD_REQUEST,
                    "按关键词检索消息必须同时限定会话、发送者或时间范围");
        }

        LambdaQueryWrapper<Message> wrapper = Wrappers.<Message>lambdaQuery()
                .eq(query.getConversationId() != null, Message::getConversationId, query.getConversationId())
                .eq(query.getFromUserId() != null, Message::getFromUserId, query.getFromUserId())
                .eq(query.getMsgType() != null, Message::getMsgType, query.getMsgType())
                // 默认不过滤 is_recalled：撤回本身就是要看的事件，要只看撤回过的才拉这个条件
                .eq(Boolean.TRUE.equals(query.getRecalledOnly()), Message::getRecalled, 1)
                .ge(query.getStartTime() != null, Message::getSendTime, query.getStartTime())
                .le(query.getEndTime() != null, Message::getSendTime, query.getEndTime())
                .like(keyword != null, Message::getContent, keyword)
                // 固定按主键倒序，不接受排序参数：理由见 AdminMessageSpi#pageMessages
                .orderByDesc(Message::getId);

        Page<Message> page = messageMapper.selectPage(new Page<>(current, size), wrapper);
        List<Message> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }

        Map<Long, ConversationBriefDTO> briefs = new HashMap<>();
        Map<Long, List<Long>> singleMembers = new HashMap<>();
        Set<Long> userIds = new HashSet<>();
        for (Message row : rows) {
            Long conversationId = row.getConversationId();
            if (conversationId == null) {
                continue;
            }
            ConversationBriefDTO brief = briefs.containsKey(conversationId)
                    ? briefs.get(conversationId) : conversationSpi.getById(conversationId);
            if (brief == null) {
                // 会话行查不到只可能是历史脏数据；仍然要把消息行给出来，所以只留 ID 兜底
                briefs.put(conversationId, null);
                continue;
            }
            briefs.put(conversationId, brief);
            if (!isGroup(brief)) {
                List<Long> members = singleMembers.computeIfAbsent(conversationId,
                        conversationSpi::getMemberIds);
                userIds.addAll(members);
            }
            if (needSenderName(row)) {
                userIds.add(row.getFromUserId());
            }
        }
        Map<Long, UserBriefDTO> users = userIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(userIds);

        // 已读人数只在单聊算：群聊那一侧要靠成员位点逐条推算，代价与「一眼看清」不成比例
        List<Long> singleMessageIds = rows.stream()
                .filter(row -> !isGroup(briefs.get(row.getConversationId())))
                .map(Message::getId)
                .toList();
        Map<Long, Integer> readCounts = readCountsOf(singleMessageIds);

        List<AdminMessageDTO> records = new ArrayList<>(rows.size());
        for (Message row : rows) {
            ConversationBriefDTO brief = briefs.get(row.getConversationId());
            String name = conversationName(row.getConversationId(), brief,
                    singleMembers.get(row.getConversationId()), users);
            records.add(toView(row, brief, name, users, readCounts.get(row.getId()), false));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminMessageDTO detail(Long messageId) {
        if (messageId == null) {
            return null;
        }
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            return null;
        }
        ConversationBriefDTO brief = conversationSpi.getById(message.getConversationId());
        List<Long> members = isGroup(brief) ? List.of()
                : conversationSpi.getMemberIds(message.getConversationId());
        Set<Long> userIds = new HashSet<>(members);
        if (needSenderName(message)) {
            userIds.add(message.getFromUserId());
        }
        Map<Long, UserBriefDTO> users = userIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(userIds);
        Integer readCount = isGroup(brief) ? null : readCountsOf(List.of(message.getId())).get(message.getId());
        String quoteSummary = quoteSummaryOf(message.getQuoteMsgId());

        // 读全文这个动作要留痕：这是这一屏唯一会把完整正文交出去的路径
        log.info("[后台消息档案] messageId={}, conversationId={}, 正文 {} 字, 引用 {}, 扩展 {}",
                messageId, message.getConversationId(), message.getContent() == null ? 0 : message.getContent().length(),
                quoteSummary != null, message.getExtra() != null);
        return toView(message, brief, conversationName(message.getConversationId(), brief, members, users),
                users, readCount, true, quoteSummary);
    }

    @Override
    public AdminMessageOverviewDTO overview() {
        // 一次范围扫描出四个数：WHERE 用 7 天下界圈住 idx_send_time 的范围，今天与撤回口径都用 CASE 分。
        // 日界一律交给 MySQL 的 CURDATE()，不传 Java 时间戳——应用与库的时区可能不一致，
        // 而「今天发了多少」这种数字必须和库里 DATETIME 的口径同一把尺子。
        Map<String, Object> totals = firstMap(messageMapper.selectMaps(Wrappers.<Message>query()
                .select("COUNT(*) AS weekCount",
                        "COALESCE(SUM(CASE WHEN send_time >= CURDATE() THEN 1 ELSE 0 END), 0) AS todayCount",
                        "COALESCE(SUM(CASE WHEN send_time >= CURDATE() AND is_recalled = 1"
                                + " THEN 1 ELSE 0 END), 0) AS todayRecalled",
                        "COALESCE(SUM(CASE WHEN send_time >= CURDATE() AND is_recalled = 1 AND"
                                + " JSON_UNQUOTE(JSON_EXTRACT(extra, '$.recalledByAdmin')) = 'true'"
                                + " THEN 1 ELSE 0 END), 0) AS todayAdminRecalled")
                .apply("send_time >= DATE_SUB(CURDATE(), INTERVAL " + WEEK_SPAN_DAYS + " DAY)")));

        List<AdminMessageOverviewDTO.TypeSlice> slices = new ArrayList<>();
        for (Map<String, Object> row : messageMapper.selectMaps(Wrappers.<Message>query()
                .select("msg_type AS msgType", "COUNT(*) AS cnt")
                .apply("send_time >= CURDATE()")
                .groupBy("msg_type")
                .orderByDesc("cnt"))) {
            Integer code = row.get("msgType") instanceof Number number ? number.intValue() : null;
            MsgType type = typeOf(code);
            slices.add(AdminMessageOverviewDTO.TypeSlice.builder()
                    .msgType(code)
                    .msgTypeDesc(type == null ? "未知" : type.getDesc())
                    .count(asLong(row.get("cnt")))
                    .build());
        }

        int retentionDays = imProperties.getMessage().getRetentionDays();
        return AdminMessageOverviewDTO.builder()
                .todayCount(asLong(totals.get("todayCount")))
                .weekCount(asLong(totals.get("weekCount")))
                .todayRecalled(asLong(totals.get("todayRecalled")))
                .todayAdminRecalled(asLong(totals.get("todayAdminRecalled")))
                .expireDays(retentionDays)
                .oldestRetainedTime(messageMapper.selectOldestSendTime())
                .typeSlices(slices)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public AdminMessageDTO forceRecall(Long messageId, Long adminId) {
        BusinessException.throwIf(messageId == null, ResultCode.BAD_REQUEST, "消息 ID 必填");
        // 只借 force 这一道闸，其余判断（资金类拒绝、系统通知拒绝、幂等）全在 recallInternal 里同一条路
        messageService.adminRecall(messageId, adminId);
        AdminMessageDTO view = detail(messageId);
        BusinessException.throwIf(view == null, ResultCode.MESSAGE_NOT_FOUND);
        return view;
    }

    /* ==================== 装配 ==================== */

    private AdminMessageDTO toView(Message message, ConversationBriefDTO brief, String conversationName,
                                   Map<Long, UserBriefDTO> users, Integer readCount, boolean full) {
        return toView(message, brief, conversationName, users, readCount, full, null);
    }

    /**
     * 实体转后台视图。
     *
     * @param full 档案模式：{@code false} 时正文按 {@value #CONTENT_MAX} 字截断并置
     *             {@code contentTruncated}，{@code true} 时给全文
     */
    private AdminMessageDTO toView(Message message, ConversationBriefDTO brief, String conversationName,
                                   Map<Long, UserBriefDTO> users, Integer readCount, boolean full,
                                   String quoteSummary) {
        MsgType type = typeOf(message.getMsgType());
        String raw = message.getContent();
        int length = raw == null ? 0 : raw.length();
        boolean truncated = !full && length > CONTENT_MAX;
        return AdminMessageDTO.builder()
                .messageId(message.getId())
                .conversationId(message.getConversationId())
                .conversationName(conversationName)
                .conversationType(brief == null ? null : brief.getType())
                .fromUserId(message.getFromUserId())
                .fromName(fromName(message, users))
                .msgType(message.getMsgType())
                .msgTypeDesc(type == null ? "未知" : type.getDesc())
                .content(truncated ? raw.substring(0, CONTENT_MAX) : raw)
                .contentTruncated(truncated)
                .contentLength(length)
                .extraSummary(extraSummary(message, type))
                .seq(message.getSeq())
                .quoteMsgId(message.getQuoteMsgId())
                .quoteSummary(quoteSummary)
                .recalled(message.getRecalled())
                .recallTime(message.getRecallTime())
                .sendTime(message.getSendTime())
                .readCount(readCount)
                .build();
    }

    /**
     * 引用那条消息的一句话摘要，只在档案里给（列表不额外为每行多查一次）。
     *
     * <p>原消息已被物理清理（保留期到了）时给一个占位而不是 {@code null}：
     * 管理员看到 {@code quoteMsgId} 有值却没有摘要，会以为是查询出了毛病。
     */
    private String quoteSummaryOf(Long quoteMsgId) {
        if (quoteMsgId == null) {
            return null;
        }
        Message quote = messageMapper.selectById(quoteMsgId);
        if (quote == null) {
            return "原消息已清理 #" + quoteMsgId;
        }
        UserBriefDTO sender = needSenderName(quote) ? userQuerySpi.getById(quote.getFromUserId()) : null;
        return TextUtil.summary(MessageConvert.summaryOf(quote, displayOf(sender)), SUMMARY_MAX);
    }

    /**
     * 会话展示名：群聊直接取群名（{@code ConversationSpi} 那条路已经补齐），
     * 单聊拼「A ↔ B」——{@code brief} 的 {@code viewerId} 为空时拿不到「对方」，
     * 而两位成员对管理员才是那条私聊的身份。
     */
    private String conversationName(Long conversationId, ConversationBriefDTO brief,
                                    List<Long> members, Map<Long, UserBriefDTO> users) {
        if (brief == null) {
            return conversationId == null ? null : CONV_FALLBACK + conversationId;
        }
        if (isGroup(brief)) {
            return TextUtil.isBlank(brief.getName()) ? CONV_FALLBACK + conversationId : brief.getName();
        }
        if (members == null || members.isEmpty()) {
            return CONV_FALLBACK + conversationId;
        }
        List<String> names = new ArrayList<>(members.size());
        for (Long memberId : members) {
            names.add(displayOf(users.get(memberId)));
        }
        return String.join(" ↔ ", names);
    }

    /**
     * 发送者展示名。三层兜底的顺序不能换：
     * <ol>
     *   <li>机器人消息的 {@code fromUserId} 是 {@code im_group_robot} 的 ID，<b>不是用户 ID</b>，
     *       拿它去查用户只会得到一个错误的昵称或「已注销用户」，所以先取 {@code extra} 里的名称快照；</li>
     *   <li>系统通知的发送者 ID 为 0，没有资料可查；</li>
     *   <li>剩下的才是真实用户，注销了也保底显示占位而不是让整行空着。</li>
     * </ol>
     */
    private String fromName(Message message, Map<Long, UserBriefDTO> users) {
        MessageExtra extra = message.getExtra();
        if (extra != null && TextUtil.isNotBlank(extra.getRobotName())) {
            return extra.getRobotName();
        }
        if (ImConstants.SYSTEM_USER_ID.equals(message.getFromUserId()) || message.isSystem()) {
            return SYSTEM_NAME;
        }
        if (message.getFromUserId() == null) {
            return null;
        }
        UserBriefDTO sender = users.get(message.getFromUserId());
        if (sender == null) {
            // 用户已注销：那一行消息还在，仍然要能显示出「谁发的」，哪怕只剩一个 ID
            return DELETED_NICKNAME + " #" + message.getFromUserId();
        }
        return displayOf(sender);
    }

    /** 机器人消息与系统消息都不需要（也不应该）去 im_user 查资料 */
    private static boolean needSenderName(Message message) {
        if (message.getFromUserId() == null || ImConstants.SYSTEM_USER_ID.equals(message.getFromUserId())) {
            return false;
        }
        MessageExtra extra = message.getExtra();
        return extra == null || TextUtil.isBlank(extra.getRobotName());
    }

    private static String displayOf(UserBriefDTO user) {
        if (user == null) {
            return DELETED_NICKNAME;
        }
        return TextUtil.isBlank(user.getNickname()) ? user.getUsername() : user.getNickname();
    }

    /**
     * 单聊已读人数：数 {@code im_message_read} 里 {@code read_time} 非空的行。
     *
     * <p>单聊最多 1（回执只为「别人发的消息」写，自己不算自己的已读），所以这里给的是
     * 0 或 1；真正的「对方读没读」在用户侧气泡上已经有，这一列只是让取证时能一并看到。
     */
    private Map<Long, Integer> readCountsOf(List<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>(messageIds.size());
        for (MessageRead row : messageReadMapper.selectByMessageIds(messageIds)) {
            if (row.getReadTime() != null) {
                counts.merge(row.getMessageId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * {@code extra} 的可读摘要：把那些「{@code content} 里看不出来」的信息补上
     * （附件名与尺寸、通话时长、@ 了谁、转账金额、接龙进度等）。
     *
     * <p><b>不含 {@code fileUrl}</b>：那是受控访问地址，后台要看附件本体应当走文件那一屏的鉴权。
     * 也不含 {@code flashViewedBy} 这类会一直变的名单——摘要要的是「发的是什么」，不是「现在谁看过」。
     *
     * <p>{@code recalledByAdmin} 这个键在这里出现是刻意的：它正是「这条是被后台撤的」的可见凭证，
     * 列表里一眼能看到，比让管理员再去翻审计日志便宜。
     */
    private static String extraSummary(Message message, MsgType type) {
        MessageExtra extra = message.getExtra();
        if (extra == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        switch (type == null ? MsgType.TEXT : type) {
            case IMAGE -> {
                if (Boolean.TRUE.equals(extra.getFlash())) {
                    parts.add("闪照" + (extra.getFlashSeconds() == null ? "" : " " + extra.getFlashSeconds() + " 秒"));
                }
                appendSize(parts, extra);
            }
            case FILE -> {
                if (TextUtil.isNotBlank(extra.getFileName())) {
                    parts.add(extra.getFileName());
                }
                appendSize(parts, extra);
            }
            case VOICE -> {
                parts.add("语音 " + durationText(extra.getDuration()));
                appendSize(parts, extra);
            }
            case CALL -> {
                parts.add(("video".equals(extra.getCallMediaType()) ? "视频通话 " : "语音通话 ")
                        + durationText(extra.getDuration()));
                if (extra.getCallParticipantCount() != null) {
                    parts.add(extra.getCallParticipantCount() + " 人");
                }
                if (Boolean.TRUE.equals(extra.getCallScreenRecorded())) {
                    parts.add("有录屏");
                }
            }
            case CARD -> parts.add("名片：" + fallback(extra.getCardNickname(), "联系人"));
            case LOCATION -> {
                parts.add("位置：" + fallback(extra.getLocationName(), "地图位置"));
                if (extra.getLatitude() != null && extra.getLongitude() != null) {
                    parts.add(extra.getLatitude() + ", " + extra.getLongitude());
                }
            }
            case MERGED_FORWARD -> parts.add("聊天记录：" + fallback(extra.getMergedTitle(), "会话记录")
                    + "（" + (extra.getMergedMessages() == null ? 0 : extra.getMergedMessages().size()) + " 条）");
            case VOTE -> parts.add("投票：" + fallback(extra.getVoteTitle(), message.getContent())
                    + "（" + (extra.getVoteOptions() == null ? 0 : extra.getVoteOptions().size()) + " 项"
                    + (Boolean.TRUE.equals(extra.getVoteMultiple()) ? "，多选" : "")
                    + (Boolean.TRUE.equals(extra.getVoteAnonymous()) ? "，匿名" : "") + "）");
            case CHAIN -> parts.add("接龙：" + fallback(extra.getChainTitle(), message.getContent())
                    + "（" + (extra.getChainItems() == null ? 0 : extra.getChainItems().size()) + " 条"
                    + (Boolean.TRUE.equals(extra.getChainClosed()) ? "，已截止" : "") + "）");
            case TRANSFER -> parts.add("转账 " + (extra.getTransferAmount() == null ? 0 : extra.getTransferAmount())
                    + " 积分 → " + fallback(extra.getTransferToNickname(), "收款人")
                    + (TextUtil.isBlank(extra.getTransferMemo()) ? "" : "，附言：" + extra.getTransferMemo()));
            case RED_PACKET -> parts.add("红包 " + (extra.getPacketAmount() == null ? 0 : extra.getPacketAmount())
                    + " 积分 / " + (extra.getPacketCount() == null ? 1 : extra.getPacketCount()) + " 份");
            case SYSTEM -> {
                if (TextUtil.isNotBlank(extra.getAction())) {
                    parts.add("动作：" + extra.getAction());
                }
            }
            case ROBOT -> {
                if (TextUtil.isNotBlank(extra.getRobotName())) {
                    parts.add("机器人：" + extra.getRobotName());
                }
            }
            case TEXT -> {
                // 文本一般没有扩展信息，下面的通用标记够用
            }
        }
        if (Boolean.TRUE.equals(extra.getAtAll())) {
            parts.add("@ 全员");
        } else if (extra.getAtUserIds() != null && !extra.getAtUserIds().isEmpty()) {
            parts.add("@ " + extra.getAtUserIds().size() + " 人");
        }
        if (Boolean.TRUE.equals(extra.getWatermark())) {
            parts.add("带水印");
        }
        if (Boolean.TRUE.equals(extra.getEdited())) {
            parts.add("已编辑");
        }
        if (Boolean.TRUE.equals(extra.getRecalledByAdmin())) {
            parts.add("后台强制撤回");
        }
        if (parts.isEmpty()) {
            return null;
        }
        return TextUtil.summary(String.join(" · ", parts), SUMMARY_MAX);
    }

    private static void appendSize(List<String> parts, MessageExtra extra) {
        String size = sizeText(extra.getFileSize());
        if (size != null) {
            parts.add(size);
        }
        if (extra.getWidth() != null && extra.getHeight() != null) {
            parts.add(extra.getWidth() + "×" + extra.getHeight());
        }
        if (TextUtil.isNotBlank(extra.getContentType())) {
            parts.add(extra.getContentType());
        }
    }

    /* ==================== 小工具 ==================== */

    private static boolean isGroup(ConversationBriefDTO brief) {
        return brief != null && brief.getType() != null && ConvType.GROUP.getCode() == brief.getType();
    }

    /** 脏数据或新增类型码不能把整页打挂：{@code MsgType.of} 对这些是抛异常的 */
    private static MsgType typeOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (MsgType type : MsgType.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        return null;
    }

    private static String durationText(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return "0 秒";
        }
        if (seconds < 60) {
            return seconds + " 秒";
        }
        int minute = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? minute + " 分钟" : minute + " 分 " + rest + " 秒";
    }

    private static String sizeText(Long bytes) {
        if (bytes == null || bytes < 0) {
            return null;
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024d);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024d / 1024d);
        }
        return String.format("%.2f GB", bytes / 1024d / 1024d / 1024d);
    }

    private static String fallback(String value, String placeholder) {
        return TextUtil.isBlank(value) ? placeholder : value;
    }

    private static Map<String, Object> firstMap(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

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
