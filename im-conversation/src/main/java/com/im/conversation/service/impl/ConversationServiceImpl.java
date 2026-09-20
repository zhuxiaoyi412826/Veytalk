package com.im.conversation.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.GroupBriefDTO;
import com.im.common.domain.MemberPositionDTO;
import com.im.common.domain.MessageEvent;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.ConvType;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.GroupSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.conversation.dto.po.ConversationView;
import com.im.conversation.dto.vo.ConversationVO;
import com.im.conversation.entity.Conversation;
import com.im.conversation.entity.ConversationMember;
import com.im.conversation.mapper.ConversationMapper;
import com.im.conversation.mapper.ConversationMemberMapper;
import com.im.conversation.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话服务实现。
 *
 * <p>并发与幂等的三道防线：
 * <ol>
 *   <li>会话创建靠唯一键 {@code uk_biz_key}，捕获 {@link DuplicateKeyException} 后回查，双方同时发起也只会有一条会话</li>
 *   <li>未读数一律走数据库端 {@code unread_count = unread_count + 1} 原子自增，不做「读-改-写」</li>
 *   <li>会话摘要更新带 {@code last_msg_time <= ?} 防护，乱序到达的旧消息不会覆盖更新的摘要</li>
 * </ol>
 *
 * <p>对 im-group / im-friend / im-message / im-websocket 的调用全部通过 {@link ObjectProvider} 弱引用，
 * 未装配时降级为「只返回本模块能确定的数据」，保证会话模块可独立启动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    /** 未读总数缓存时长；未读变动时主动失效，TTL 仅作兜底 */
    private static final Duration UNREAD_CACHE_TTL = Duration.ofMinutes(30);

    /** @ 提醒集合的保留时长，避免长期堆积 */
    private static final Duration AT_FLAG_TTL = Duration.ofDays(7);

    /** 会话摘要长度上限，DDL 中 last_msg_content 为 VARCHAR(512) */
    private static final int SUMMARY_MAX_LENGTH = 200;

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;
    private final UserQuerySpi userQuerySpi;
    private final RedisUtil redisUtil;
    private final ObjectProvider<GroupSpi> groupSpiProvider;
    private final ObjectProvider<FriendRelationSpi> friendRelationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;
    private final ObjectProvider<PushSpi> pushSpiProvider;

    /* ==================== 面向前端 ==================== */

    @Override
    public List<ConversationVO> list(Long userId) {
        List<ConversationView> views = memberMapper.selectViewsByUserId(userId);
        if (views.isEmpty()) {
            return Collections.emptyList();
        }
        return assemble(userId, views);
    }

    @Override
    public ConversationVO detail(Long userId, Long conversationId) {
        ConversationView view = memberMapper.selectView(userId, conversationId);
        // 查不到视图等价于「不是成员」，与会话本身是否存在同样处理，避免被用来探测会话 ID
        BusinessException.throwIf(view == null, ResultCode.CONVERSATION_NO_PERMISSION);
        return assemble(userId, List.of(view)).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createSingle(Long userId, Long targetUserId) {
        BusinessException.throwIf(targetUserId == null, ResultCode.BAD_REQUEST, "目标用户不能为空");
        BusinessException.throwIf(userId.equals(targetUserId), ResultCode.BAD_REQUEST, "不能与自己创建会话");
        BusinessException.throwUnless(userQuerySpi.exists(targetUserId), ResultCode.USER_NOT_FOUND);

        FriendRelationSpi friendSpi = friendRelationSpiProvider.getIfAvailable();
        if (friendSpi != null) {
            // 单向阻断：只拦「对方已把我拉黑」这一方向；我拉黑了对方时仍可主动发起，
            // 消息发送成功会在 im-message 里顺带自动解除拉黑，因此这里不再拦「我拉黑对方」
            BusinessException.throwIf(friendSpi.isBlockedBy(targetUserId, userId), ResultCode.FRIEND_BLOCKED);
            BusinessException.throwUnless(friendSpi.isFriend(userId, targetUserId), ResultCode.FRIEND_NOT_FOUND);
        }
        return getOrCreateSingle(userId, targetUserId);
    }

    @Override
    public void markRead(Long userId, Long conversationId, Long lastAckSeq) {
        requireMember(userId, conversationId);
        clearUnread(userId, conversationId, lastAckSeq);
        // 已读回执（写 im_message_read 并推送给发送方）由 im-message 负责，会话模块只维护自己的未读状态
        MessageSpi messageSpi = messageSpiProvider.getIfAvailable();
        if (messageSpi != null) {
            try {
                messageSpi.markRead(userId, conversationId, lastAckSeq);
            } catch (Exception e) {
                log.warn("[会话已读] 同步已读回执失败，未读数已清零: conversationId={}, {}", conversationId, e.getMessage());
            }
        }
    }

    @Override
    public void setTop(Long userId, Long conversationId, boolean enabled) {
        requireMember(userId, conversationId);
        memberMapper.updateTop(conversationId, userId, enabled);
    }

    @Override
    public void setMute(Long userId, Long conversationId, boolean enabled) {
        requireMember(userId, conversationId);
        memberMapper.updateMuted(conversationId, userId, enabled);
    }

    @Override
    public void hide(Long userId, Long conversationId, boolean hidden) {
        requireMember(userId, conversationId);
        memberMapper.updateHidden(conversationId, userId, hidden);
        log.info("[会话隐藏] userId={}, conversationId={}, hidden={}", userId, conversationId, hidden);
    }

    @Override
    public long unreadTotal(Long userId) {
        String key = RedisKeys.unreadTotal(userId);
        String cached = redisUtil.get(key);
        if (cached != null) {
            try {
                return Long.parseLong(cached.trim());
            } catch (NumberFormatException e) {
                log.debug("[未读缓存] 缓存值非法，回源重算: key={}, value={}", key, cached);
            }
        }
        long total = memberMapper.sumUnread(userId);
        redisUtil.set(key, String.valueOf(total), UNREAD_CACHE_TTL);
        return total;
    }

    /* ==================== 跨模块原语 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateSingle(Long userA, Long userB) {
        BusinessException.throwIf(userA == null || userB == null, ResultCode.BAD_REQUEST, "会话双方不能为空");
        BusinessException.throwIf(userA.equals(userB), ResultCode.BAD_REQUEST, "不能与自己创建会话");
        // 单聊 biz_key 用双方 ID 升序拼接，保证 A->B 与 B->A 命中同一条会话
        return getOrCreate(ConvType.singleBizKey(userA, userB), ConvType.SINGLE.getCode(), null, List.of(userA, userB));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long getOrCreateGroup(Long groupId, Collection<Long> memberIds) {
        BusinessException.throwIf(groupId == null, ResultCode.BAD_REQUEST, "群 ID 不能为空");
        Set<Long> members = new LinkedHashSet<>();
        if (memberIds != null) {
            memberIds.stream().filter(Objects::nonNull).forEach(members::add);
        }
        BusinessException.throwIf(members.isEmpty(), ResultCode.BAD_REQUEST, "群成员不能为空");
        return getOrCreate(ConvType.groupBizKey(groupId), ConvType.GROUP.getCode(), groupId, members);
    }

    @Override
    public Long findSingle(Long userA, Long userB) {
        if (userA == null || userB == null || userA.equals(userB)) {
            return null;
        }
        Conversation conversation = conversationMapper.selectByBizKey(ConvType.singleBizKey(userA, userB));
        return conversation == null ? null : conversation.getId();
    }

    /**
     * 只走唯一键 {@code uk_biz_key} 查一次，不创建也不补齐成员：
     * 群详情是纯读场景，在这里写库会把一次查询变成隐式建会话。
     */
    @Override
    public Long findGroup(Long groupId) {
        if (groupId == null) {
            return null;
        }
        Conversation conversation = conversationMapper.selectByBizKey(ConvType.groupBizKey(groupId));
        return conversation == null ? null : conversation.getId();
    }

    @Override
    public ConversationBriefDTO brief(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        return conversation == null ? null : toBrief(conversation, null);
    }

    @Override
    public Conversation requireConversation(Long conversationId) {
        BusinessException.throwIf(conversationId == null, ResultCode.BAD_REQUEST, "会话 ID 不能为空");
        Conversation conversation = conversationMapper.selectById(conversationId);
        BusinessException.throwIf(conversation == null, ResultCode.CONVERSATION_NOT_FOUND);
        return conversation;
    }

    @Override
    public ConversationMember requireMember(Long userId, Long conversationId) {
        requireConversation(conversationId);
        ConversationMember member = memberMapper.selectByConvAndUser(conversationId, userId);
        BusinessException.throwIf(member == null, ResultCode.CONVERSATION_NO_PERMISSION);
        return member;
    }

    @Override
    public List<Long> memberIds(Long conversationId) {
        return conversationId == null ? Collections.emptyList() : memberMapper.selectMemberIds(conversationId);
    }

    @Override
    public boolean isMember(Long conversationId, Long userId) {
        if (conversationId == null || userId == null) {
            return false;
        }
        return memberMapper.selectByConvAndUser(conversationId, userId) != null;
    }

    @Override
    public Long targetIdOf(Long conversationId, Long viewerId) {
        if (conversationId == null) {
            return null;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return null;
        }
        if (conversation.isGroup()) {
            return conversation.getTargetId();
        }
        // 单聊的 target_id 在库里是 NULL，对方只能从成员表按「不是我」反解
        if (viewerId == null) {
            return null;
        }
        return memberMapper.selectPeers(List.of(conversationId), viewerId).stream()
                .map(ConversationMember::getUserId)
                .findFirst()
                .orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onNewMessage(MessageEvent event) {
        if (event == null || event.getConversationId() == null) {
            return;
        }
        Long conversationId = event.getConversationId();

        conversationMapper.updateLastMessage(conversationId, event.getMessageId(),
                TextUtil.summary(event.getSummary(), SUMMARY_MAX_LENGTH), event.getMsgType(), event.getSendTime());

        List<Long> receivers = distinctReceivers(event);
        if (receivers.isEmpty()) {
            return;
        }
        memberMapper.increaseUnread(conversationId, receivers, 1);
        // 用户曾本端隐藏过该会话，对方再发消息时应当重新露出
        memberMapper.reveal(List.of(conversationId), receivers);
        receivers.forEach(this::invalidateUnreadCache);

        markAt(conversationId, event, receivers);
    }

    @Override
    public void onMessageRecalled(Long conversationId, Long messageId, String summary) {
        if (conversationId == null || messageId == null) {
            return;
        }
        // 只有撤回的正是最后一条消息时才改写摘要，否则会话列表会莫名跳变；
        // 这里刻意不更新 last_msg_time，撤回不该把会话重新顶到列表最前
        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .set(Conversation::getLastMsgContent, TextUtil.summary(summary, SUMMARY_MAX_LENGTH))
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getLastMsgId, messageId));
    }

    @Override
    public void clearUnread(Long userId, Long conversationId, Long lastAckSeq) {
        memberMapper.resetUnread(conversationId, userId, lastAckSeq);
        clearAtFlag(userId, conversationId);
        invalidateUnreadCache(userId);
        pushUnread(userId, conversationId, 0);
    }

    @Override
    public void advanceAck(Long userId, Long conversationId, Long lastAckSeq) {
        if (userId == null || conversationId == null || lastAckSeq == null) {
            return;
        }
        memberMapper.advanceAck(conversationId, userId, lastAckSeq);
    }

    @Override
    public Map<Long, Long> ackPositions(Long userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        Map<Long, Long> positions = new HashMap<>();
        for (ConversationMember member : memberMapper.selectAckPositions(userId)) {
            positions.put(member.getConversationId(), member.getLastAckSeq() == null ? 0L : member.getLastAckSeq());
        }
        return positions;
    }

    @Override
    public long readPosition(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return 0L;
        }
        return memberMapper.selectReadSeq(conversationId, userId);
    }

    @Override
    public List<MemberPositionDTO> memberPositions(Long conversationId, Long excludeUserId) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        return memberMapper.selectMemberPositions(conversationId, excludeUserId).stream()
                .map(member -> MemberPositionDTO.builder()
                        .ackSeq(member.getLastAckSeq() == null ? 0L : member.getLastAckSeq())
                        .readSeq(member.getLastReadSeq() == null ? 0L : member.getLastReadSeq())
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addMembers(Long conversationId, Collection<Long> userIds) {
        requireConversation(conversationId);
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        ensureMembers(conversationId, userIds);
        log.info("[会话加人] conversationId={}, 新增候选={}", conversationId, userIds.size());
    }

    @Override
    public void removeMember(Long conversationId, Long userId) {
        if (conversationId == null || userId == null) {
            return;
        }
        memberMapper.delete(Wrappers.<ConversationMember>lambdaQuery()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
        log.info("[会话移除成员] conversationId={}, userId={}", conversationId, userId);
    }

    @Override
    public List<ConversationBriefDTO> listBriefByUser(Long userId) {
        List<ConversationView> views = memberMapper.selectViewsByUserId(userId);
        if (views.isEmpty()) {
            return Collections.emptyList();
        }
        return assemble(userId, views).stream().map(ConversationServiceImpl::toBrief).toList();
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 会话创建的统一入口：先查后建，并用唯一键 {@code uk_biz_key} 兜住并发。
     */
    private Long getOrCreate(String bizKey, int type, Long targetId, Collection<Long> members) {
        Conversation existing = conversationMapper.selectByBizKey(bizKey);
        if (existing != null) {
            // 会话已存在时补齐可能缺失的成员行，例如历史数据不一致或一方曾被移除
            ensureMembers(existing.getId(), members);
            return existing.getId();
        }
        Conversation conversation = new Conversation();
        conversation.setType(type);
        conversation.setTargetId(targetId);
        conversation.setBizKey(bizKey);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            Conversation raced = conversationMapper.selectByBizKey(bizKey);
            BusinessException.throwIf(raced == null, ResultCode.CONVERSATION_CREATE_FAILED);
            log.debug("[创建会话] 并发命中唯一键，复用已存在会话: bizKey={}", bizKey);
            ensureMembers(raced.getId(), members);
            return raced.getId();
        }
        insertMembers(conversation.getId(), members);
        log.info("[创建会话] id={}, type={}, bizKey={}, 成员数={}", conversation.getId(), type, bizKey, members.size());
        return conversation.getId();
    }

    private void insertMembers(Long conversationId, Collection<Long> userIds) {
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            try {
                memberMapper.insert(newMember(conversationId, userId));
            } catch (DuplicateKeyException e) {
                // uk_conv_user 兜底：并发添加同一成员时静默跳过
                log.debug("[会话成员] 已存在，跳过: conversationId={}, userId={}", conversationId, userId);
            }
        }
    }

    private void ensureMembers(Long conversationId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<Long> existing = new HashSet<>(memberMapper.selectMemberIds(conversationId));
        List<Long> missing = userIds.stream().filter(id -> id != null && !existing.contains(id)).toList();
        if (!missing.isEmpty()) {
            insertMembers(conversationId, missing);
        }
    }

    private ConversationMember newMember(Long conversationId, Long userId) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setUnreadCount(0);
        member.setLastAckSeq(0L);
        member.setLastReadSeq(0L);
        member.setTop(0);
        member.setMuted(0);
        member.setHidden(0);
        member.setAtFlag(0);
        return member;
    }

    /**
     * 把数据库投影组装为前端视图：批量补齐单聊对方资料、好友备注、群信息与在线状态。
     *
     * <p>每类数据源最多一次批量调用，一次列表渲染不会产生 N+1。
     */
    private List<ConversationVO> assemble(Long userId, List<ConversationView> views) {
        List<Long> singleIds = views.stream().filter(this::isSingle).map(ConversationView::getId).toList();
        Map<Long, Long> peerIds = resolvePeers(singleIds, userId);

        Set<Long> peerUserIds = new HashSet<>(peerIds.values());
        Map<Long, UserBriefDTO> users = peerUserIds.isEmpty()
                ? Collections.emptyMap() : userQuerySpi.listByIds(peerUserIds);
        Map<Long, String> remarks = remarksOf(userId, peerUserIds);

        List<Long> groupIds = views.stream()
                .filter(view -> !isSingle(view))
                .map(ConversationView::getTargetId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, GroupBriefDTO> groups = groupBriefs(groupIds);

        List<ConversationVO> result = new ArrayList<>(views.size());
        for (ConversationView view : views) {
            result.add(toVO(view, peerIds, users, remarks, groups));
        }
        return result;
    }

    /**
     * 解析单聊会话的对方 ID。单聊只有两个成员，{@code putIfAbsent} 保证异常数据下也只取一个。
     */
    private Map<Long, Long> resolvePeers(Collection<Long> singleConversationIds, Long userId) {
        if (singleConversationIds == null || singleConversationIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> peers = new HashMap<>();
        for (ConversationMember member : memberMapper.selectPeers(singleConversationIds, userId)) {
            peers.putIfAbsent(member.getConversationId(), member.getUserId());
        }
        return peers;
    }

    private Map<Long, String> remarksOf(Long userId, Collection<Long> peerIds) {
        FriendRelationSpi friendSpi = friendRelationSpiProvider.getIfAvailable();
        if (friendSpi == null || peerIds == null || peerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return friendSpi.getRemarks(userId, peerIds);
        } catch (Exception e) {
            log.warn("[会话列表] 批量获取好友备注失败，回退展示昵称: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private Map<Long, GroupBriefDTO> groupBriefs(Collection<Long> groupIds) {
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null || groupIds == null || groupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<Long, GroupBriefDTO> briefs = groupSpi.listBriefs(groupIds);
            return briefs == null ? Collections.emptyMap() : briefs;
        } catch (Exception e) {
            log.warn("[会话列表] 批量获取群信息失败，回退展示占位名称: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private ConversationVO toVO(ConversationView view, Map<Long, Long> peerIds, Map<Long, UserBriefDTO> users,
                               Map<Long, String> remarks, Map<Long, GroupBriefDTO> groups) {
        ConversationVO.ConversationVOBuilder builder = ConversationVO.builder()
                .conversationId(view.getId())
                .type(view.getType())
                .typeDesc(ConvType.of(view.getType()).getDesc())
                .unreadCount(view.getUnreadCount() == null ? 0 : view.getUnreadCount())
                .atFlag(view.getAtFlag() != null && view.getAtFlag() == 1)
                .lastMsgId(view.getLastMsgId())
                .lastMsgContent(view.getLastMsgContent())
                .lastMsgType(view.getLastMsgType())
                .lastMsgTime(view.getLastMsgTime())
                .top(view.getTop() != null && view.getTop() == 1)
                .topTime(view.getTopTime())
                .muted(view.getMuted() != null && view.getMuted() == 1)
                .hidden(false)
                .lastAckSeq(view.getLastAckSeq());

        if (isSingle(view)) {
            Long peerId = peerIds.get(view.getId());
            UserBriefDTO peer = peerId == null ? null : users.get(peerId);
            String remark = peerId == null ? null : remarks.get(peerId);
            builder.targetId(peerId)
                    .name(singleName(remark, peer, peerId))
                    .avatar(peer == null ? null : peer.getAvatar())
                    .online(peer != null && Boolean.TRUE.equals(peer.getOnline()))
                    .remark(remark);
        } else {
            GroupBriefDTO group = view.getTargetId() == null ? null : groups.get(view.getTargetId());
            builder.targetId(view.getTargetId())
                    .name(group == null || TextUtil.isBlank(group.getName()) ? "群聊" : group.getName())
                    .avatar(group == null ? null : group.getAvatar())
                    // 群聊没有单一的「对方在线」语义，固定 false 让前端不渲染在线绿点
                    .online(false)
                    .memberCount(group == null ? null : group.getMemberCount());
        }
        return builder.build();
    }

    /**
     * 单聊展示名优先级：好友备注 &gt; 昵称 &gt; 账号 &gt; 兜底文案。
     */
    private String singleName(String remark, UserBriefDTO peer, Long peerId) {
        if (TextUtil.isNotBlank(remark)) {
            return remark;
        }
        if (peer != null) {
            if (TextUtil.isNotBlank(peer.getNickname())) {
                return peer.getNickname();
            }
            if (TextUtil.isNotBlank(peer.getUsername())) {
                return peer.getUsername();
            }
        }
        return peerId == null ? "未知用户" : "用户" + peerId;
    }

    private boolean isSingle(ConversationView view) {
        return view.getType() != null && view.getType() == ConvType.SINGLE.getCode();
    }

    /**
     * 接收者去重并剔除发送者本人：发送者不该给自己计未读。
     */
    private List<Long> distinctReceivers(MessageEvent event) {
        if (event.getReceiverIds() == null || event.getReceiverIds().isEmpty()) {
            return Collections.emptyList();
        }
        Long sender = event.getFromUserId();
        return event.getReceiverIds().stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(sender))
                .distinct()
                .toList();
    }

    /**
     * 记录 @ 提醒：置成员的 {@code at_flag} 并在 Redis 记录会话 ID，供前端红点与「有人@我」展示。
     *
     * <p>这里刻意不去修改 {@code is_muted}：免打扰是用户的长期偏好，被 @ 一次就永久清掉，
     * 用户下次还得重新开启。用独立的 at 标记既能穿透免打扰做出提醒，又不破坏用户设置。
     */
    private void markAt(Long conversationId, MessageEvent event, List<Long> receivers) {
        boolean atAll = Boolean.TRUE.equals(event.getAtAll());
        List<Long> atIds;
        if (atAll) {
            atIds = receivers;
        } else if (event.getAtUserIds() == null || event.getAtUserIds().isEmpty()) {
            return;
        } else {
            Set<Long> receiverSet = new HashSet<>(receivers);
            atIds = event.getAtUserIds().stream()
                    .filter(Objects::nonNull)
                    .filter(receiverSet::contains)
                    .distinct()
                    .toList();
        }
        if (atIds.isEmpty()) {
            return;
        }
        memberMapper.markAtFlag(conversationId, atIds);
        for (Long userId : atIds) {
            try {
                String key = RedisKeys.atMe(userId);
                redisUtil.sAdd(key, String.valueOf(conversationId));
                redisUtil.expire(key, AT_FLAG_TTL);
            } catch (Exception e) {
                log.debug("[@提醒] 写入 Redis 失败，数据库标记仍然有效: userId={}, {}", userId, e.getMessage());
            }
        }
    }

    private void clearAtFlag(Long userId, Long conversationId) {
        try {
            redisUtil.sRemove(RedisKeys.atMe(userId), String.valueOf(conversationId));
        } catch (Exception e) {
            log.debug("[@提醒] 清理 Redis 标记失败: userId={}, {}", userId, e.getMessage());
        }
    }

    /**
     * 未读总数缓存采取「变更即失效、读取时回源」策略：写入路径上不做重算，
     * 避免群聊高频消息时每条消息都触发一次 SUM 聚合。
     */
    private void invalidateUnreadCache(Long userId) {
        try {
            redisUtil.delete(RedisKeys.unreadTotal(userId));
        } catch (Exception e) {
            log.debug("[未读缓存] 失效失败，将在 TTL 到期后自动回源: userId={}, {}", userId, e.getMessage());
        }
    }

    /**
     * 把最新未读数推给该用户的全部在线端，实现多端未读同步。
     */
    private void pushUnread(Long userId, Long conversationId, int unreadCount) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("conversationId", conversationId);
            data.put("unreadCount", unreadCount);
            data.put("total", unreadTotal(userId));
            pushSpi.pushToUser(userId, WsPacket.of(WsMessageType.UNREAD, data));
        } catch (Exception e) {
            log.debug("[未读推送] 失败: userId={}, {}", userId, e.getMessage());
        }
    }

    private ConversationBriefDTO toBrief(Conversation conversation, Long viewerId) {
        ConversationBriefDTO.ConversationBriefDTOBuilder builder = ConversationBriefDTO.builder()
                .conversationId(conversation.getId())
                .type(conversation.getType())
                .targetId(conversation.getTargetId())
                .unreadCount(0)
                .lastMsgContent(conversation.getLastMsgContent())
                .lastMsgType(conversation.getLastMsgType())
                .lastMsgTime(conversation.getLastMsgTime())
                .top(false)
                .muted(false)
                .online(false);
        if (conversation.isGroup()) {
            GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
            GroupBriefDTO group = groupSpi == null ? null : groupSpi.getBrief(conversation.getTargetId(), viewerId);
            if (group != null) {
                builder.name(group.getName()).avatar(group.getAvatar());
            }
            return builder.build();
        }
        // 单聊的展示名依赖查看者视角（备注 / 对方昵称），viewerId 为空时只回会话本体信息
        Long peerId = targetIdOf(conversation.getId(), viewerId);
        builder.targetId(peerId);
        UserBriefDTO peer = peerId == null ? null : userQuerySpi.getById(peerId);
        if (peer != null) {
            builder.name(singleName(null, peer, peerId))
                    .avatar(peer.getAvatar())
                    .online(Boolean.TRUE.equals(peer.getOnline()));
        }
        return builder.build();
    }

    private static ConversationBriefDTO toBrief(ConversationVO vo) {
        return ConversationBriefDTO.builder()
                .conversationId(vo.getConversationId())
                .type(vo.getType())
                .targetId(vo.getTargetId())
                .name(vo.getName())
                .avatar(vo.getAvatar())
                .unreadCount(vo.getUnreadCount())
                .lastMsgContent(vo.getLastMsgContent())
                .lastMsgType(vo.getLastMsgType())
                .lastMsgTime(vo.getLastMsgTime())
                .top(vo.getTop())
                .muted(vo.getMuted())
                .online(vo.getOnline())
                .remark(vo.getRemark())
                .lastAckSeq(vo.getLastAckSeq())
                .build();
    }
}
