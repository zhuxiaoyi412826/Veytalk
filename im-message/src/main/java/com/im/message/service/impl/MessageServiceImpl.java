package com.im.message.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.ConversationBriefDTO;
import com.im.common.domain.MemberPositionDTO;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageEvent;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.QuotePreview;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.ConvType;
import com.im.common.enums.GroupRole;
import com.im.common.enums.MessageStatus;
import com.im.common.enums.MsgType;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.FriendRelationSpi;
import com.im.common.spi.GroupSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.RedisUtil;
import com.im.common.util.TextUtil;
import com.im.message.convert.MessageConvert;
import com.im.message.dto.po.ConversationMaxSeq;
import com.im.message.dto.req.ForwardMessageRequest;
import com.im.message.dto.req.MessageSearchQuery;
import com.im.message.dto.req.SendMessageRequest;
import com.im.message.dto.vo.MessageVO;
import com.im.message.entity.Message;
import com.im.message.entity.MessageDelete;
import com.im.message.entity.MessageRead;
import com.im.message.handler.MessageContentHandler;
import com.im.message.mapper.MessageDeleteMapper;
import com.im.message.mapper.MessageMapper;
import com.im.message.mapper.MessageReadMapper;
import com.im.message.service.MessageSequenceService;
import com.im.message.service.MessageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 消息服务实现。
 *
 * <p>三条设计约束贯穿全部方法：
 * <ol>
 *   <li><b>REST 与 WebSocket 共用一份逻辑</b>：{@code MessageController} 与 {@code MessageSpiImpl}
 *       最终都调到这里，不存在「HTTP 能发、WS 不能发」的行为漂移。</li>
 *   <li><b>推送一律在事务提交之后</b>：事务里推出去的消息可能随回滚消失，客户端收到推送后回查
 *       会拿到「消息不存在」，比收不到推送更糟。</li>
 *   <li><b>幂等有两道防线</b>：Redis 标记拦掉绝大多数重试（快、无 DB 压力），
 *       数据库唯一键 {@code uk_from_client} 兜住 Redis 失效或标记过期的漏网之鱼。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    /** 单次离线拉取上限，超出部分由客户端下次重连继续拉 */
    private static final int OFFLINE_BATCH_LIMIT = 500;

    /** 幂等标记存活时间，覆盖客户端重试窗口即可，不必长期占用内存 */
    private static final Duration IDEMPOTENT_TTL = Duration.ofMinutes(5);

    /** 清空会话聊天记录时批量写删除记录的单批行数，避免一条超大 INSERT 撑爆 SQL 长度 */
    private static final int CLEAR_BATCH = 500;

    private final MessageMapper messageMapper;
    private final MessageReadMapper messageReadMapper;
    private final MessageDeleteMapper messageDeleteMapper;
    private final MessageSequenceService sequenceService;
    private final UserQuerySpi userQuerySpi;
    private final ConversationSpi conversationSpi;
    private final RedisUtil redisUtil;
    private final ImProperties imProperties;
    private final List<MessageContentHandler> contentHandlers;

    /**
     * 群组、好友、推送三个模块对消息而言是「有则更好」：
     * 单聊在群组模块缺席时仍要能发，未装配 WebSocket 时消息也必须能落库。
     * 用 {@link ObjectProvider} 延迟取值，缺失时降级而不是启动失败。
     */
    private final ObjectProvider<GroupSpi> groupSpiProvider;
    private final ObjectProvider<FriendRelationSpi> friendSpiProvider;
    private final ObjectProvider<PushSpi> pushSpiProvider;

    /** 消息类型到内容处理器的路由表，启动时一次性摊平 */
    private final Map<MsgType, MessageContentHandler> handlerRegistry = new EnumMap<>(MsgType.class);

    /**
     * 建立类型路由表。
     *
     * <p>同一类型被两个处理器认领时直接启动失败：这种冲突在运行期只会表现为
     * 「消息校验规则随机生效一半」，排查成本远高于启动时抛一个异常。
     */
    @PostConstruct
    void initHandlerRegistry() {
        for (MessageContentHandler handler : contentHandlers) {
            for (MsgType type : handler.supportedTypes()) {
                MessageContentHandler previous = handlerRegistry.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("消息类型 " + type + " 存在多个内容处理器: "
                            + previous.getClass().getName() + " / " + handler.getClass().getName());
                }
            }
        }
        log.info("[消息] 内容处理器注册完成: {}", handlerRegistry.keySet());
    }

    /* ==================== 发送 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageDTO send(MessageSendCmd cmd) {
        BusinessException.throwIf(cmd == null || cmd.getFromUserId() == null,
                ResultCode.BAD_REQUEST, "发送者不能为空");
        MsgType type = MsgType.of(cmd.getMsgType());
        boolean internal = Boolean.TRUE.equals(cmd.getInternal());
        // 系统通知只能由服务端内部产生：客户端若能伪造 msgType=5，就能冒充系统给任意会话推送文案
        BusinessException.throwIf(type == MsgType.SYSTEM && !internal, ResultCode.MESSAGE_SEND_FORBIDDEN);

        Long conversationId = resolveConversation(cmd);
        Integer conversationType = conversationSpi.getType(conversationId);
        BusinessException.throwIf(conversationType == null, ResultCode.CONVERSATION_NOT_FOUND);
        validateSendRight(cmd, conversationId, conversationType, internal);

        String clientMsgId = resolveClientMsgId(cmd, internal);
        String idempotentKey = RedisKeys.msgIdempotent(cmd.getFromUserId(), clientMsgId);
        if (!acquireIdempotentMark(idempotentKey)) {
            return loadDuplicate(cmd.getFromUserId(), clientMsgId);
        }
        try {
            handlerOf(type).normalize(cmd);
            // 必须在 normalize 之后：文本处理器会清空 extra，先合并 @ 信息会被一起清掉
            MessageExtra extra = mergeMention(cmd, conversationType);
            // 引用校验也在 normalize 之后：转发时 cmd.quoteMsgId 为空，不会触发
            validateQuote(cmd.getQuoteMsgId(), conversationId);
            LocalDateTime now = LocalDateTime.now();

            Message message = new Message();
            message.setId(IdWorker.getId());
            message.setClientMsgId(clientMsgId);
            message.setConversationId(conversationId);
            message.setFromUserId(cmd.getFromUserId());
            message.setMsgType(type.getCode());
            message.setContent(cmd.getContent());
            message.setExtra(extra);
            message.setSeq(sequenceService.nextSeq(conversationId));
            message.setQuoteMsgId(cmd.getQuoteMsgId());
            message.setRecalled(0);
            message.setSendTime(now);
            message.setCreateTime(now);

            try {
                messageMapper.insert(message);
            } catch (DuplicateKeyException e) {
                // Redis 标记过期或不可用时的兜底，唯一键已经拦住了重复落库
                Message existing = messageMapper.selectByClientMsgId(cmd.getFromUserId(), clientMsgId);
                BusinessException.throwIf(existing == null, ResultCode.MESSAGE_DUPLICATE);
                log.warn("[消息发送] 命中唯一键幂等: fromUserId={}, clientMsgId={}", cmd.getFromUserId(), clientMsgId);
                return MessageConvert.toDTO(existing, senderOf(existing), conversationType, MessageStatus.SENT,
                        loadQuotePreview(existing.getQuoteMsgId()));
            }

            // 会话摘要与未读数必须和消息同生共死，放在事务内；
            // ConversationSpiImpl 的事务传播是 REQUIRED，会直接加入当前事务
            conversationSpi.onNewMessage(buildEvent(message, conversationType, extra, cmd));

            MessageDTO dto = MessageConvert.toDTO(message, senderOf(message), conversationType, MessageStatus.SENT,
                    loadQuotePreview(message.getQuoteMsgId()));
            if (!Boolean.FALSE.equals(cmd.getPush())) {
                afterCommit(() -> pushNewMessage(dto, conversationId, cmd.getFromUserId()));
            }
            return dto;
        } catch (RuntimeException e) {
            // 校验失败或落库异常都要释放标记，否则客户端修正内容后重发会被误判为重复
            releaseIdempotentMark(idempotentKey);
            throw e;
        }
    }

    @Override
    public MessageVO sendForView(Long userId, SendMessageRequest request) {
        MessageSendCmd cmd = MessageSendCmd.builder()
                .clientMsgId(request.getClientMsgId())
                .conversationId(request.getConversationId())
                .fromUserId(userId)
                .toUserId(request.getToUserId())
                .toGroupId(request.getToGroupId())
                .msgType(request.getMsgType())
                .content(request.getContent())
                .extra(request.getExtra())
                .atUserIds(request.getAtUserIds())
                .atAll(request.getAtAll())
                .quoteMsgId(request.getQuoteMsgId())
                .build();
        return MessageConvert.toVO(send(cmd), userId);
    }

    @Override
    public MessageDTO sendSystemNotice(Long conversationId, String content) {
        BusinessException.throwIf(conversationId == null, ResultCode.BAD_REQUEST, "会话 ID 不能为空");
        return send(MessageSendCmd.builder()
                .conversationId(conversationId)
                .fromUserId(ImConstants.SYSTEM_USER_ID)
                .msgType(MsgType.SYSTEM.getCode())
                .content(content)
                .internal(Boolean.TRUE)
                .build());
    }

    /**
     * 定位会话，优先级 {@code conversationId} &gt; {@code toUserId} &gt; {@code toGroupId}。
     *
     * <p>后两者走的是「获取或创建」，幂等：客户端从好友列表点「发消息」时手里只有对方 ID，
     * 让它先调一次会话创建接口只会多一次往返和一个可能的竞态。
     */
    private Long resolveConversation(MessageSendCmd cmd) {
        if (cmd.getConversationId() != null) {
            return cmd.getConversationId();
        }
        if (cmd.getToUserId() != null) {
            BusinessException.throwIf(cmd.getToUserId().equals(cmd.getFromUserId()),
                    ResultCode.BAD_REQUEST, "不能给自己发送消息");
            return conversationSpi.getOrCreateSingle(cmd.getFromUserId(), cmd.getToUserId());
        }
        if (cmd.getToGroupId() != null) {
            return conversationSpi.getOrCreateGroup(cmd.getToGroupId(), requireGroupSpi().getMemberIds(cmd.getToGroupId()));
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "conversationId / toUserId / toGroupId 至少填写一个");
    }

    /**
     * 发送权限校验。
     *
     * <p>会话成员校验是第一道闸：会话 ID 是雪花值猜不到，但一旦被泄露，
     * 没有这道校验就等于任何人都能往别人的会话里塞消息。
     */
    private void validateSendRight(MessageSendCmd cmd, Long conversationId, Integer conversationType, boolean internal) {
        if (internal) {
            return;
        }
        Long fromUserId = cmd.getFromUserId();
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, fromUserId),
                ResultCode.CONVERSATION_NO_PERMISSION);
        if (ConvType.GROUP.getCode() == conversationType) {
            GroupSpi groupSpi = requireGroupSpi();
            Long groupId = conversationSpi.getTargetId(conversationId, fromUserId);
            BusinessException.throwIf(groupId == null, ResultCode.GROUP_NOT_FOUND);
            BusinessException.throwUnless(groupSpi.isMember(groupId, fromUserId), ResultCode.GROUP_NOT_MEMBER);
            BusinessException.throwIf(groupSpi.isMuted(groupId, fromUserId), ResultCode.GROUP_MUTED);
            return;
        }
        FriendRelationSpi friendSpi = friendSpiProvider.getIfAvailable();
        if (friendSpi == null) {
            return;
        }
        Long targetId = conversationSpi.getTargetId(conversationId, fromUserId);
        if (targetId == null) {
            return;
        }
        // 单向阻断：只拦 B→A 的入站消息，被对方拉黑时直接报错、不落库不投递；
        // 而我拉黑了对方时仍放行——主动发消息即代表想恢复联系，发送成功顺带自动解除拉黑
        BusinessException.throwIf(friendSpi.isBlockedBy(targetId, fromUserId), ResultCode.FRIEND_BLOCKED);
        friendSpi.unblockSilently(fromUserId, targetId);
    }

    /**
     * 系统通知没有客户端重试语义，允许省略 clientMsgId，由服务端补一个满足唯一键的值。
     */
    private String resolveClientMsgId(MessageSendCmd cmd, boolean internal) {
        if (TextUtil.isNotBlank(cmd.getClientMsgId())) {
            String clientMsgId = cmd.getClientMsgId().trim();
            cmd.setClientMsgId(clientMsgId);
            return clientMsgId;
        }
        BusinessException.throwUnless(internal, ResultCode.BAD_REQUEST, "clientMsgId 不能为空");
        String generated = "sys-" + UUID.randomUUID();
        cmd.setClientMsgId(generated);
        return generated;
    }

    /**
     * 抢占幂等标记。
     *
     * @return {@code true} 表示可以继续落库；{@code false} 表示已有相同请求在处理或已完成
     */
    private boolean acquireIdempotentMark(String key) {
        try {
            return redisUtil.setIfAbsent(key, "1", IDEMPOTENT_TTL);
        } catch (Exception e) {
            // Redis 不可用时不能连消息都发不出去，退化为只靠唯一键兜底，代价是重复请求会走到 INSERT 才失败
            log.warn("[消息幂等] Redis 不可用，降级为仅依赖唯一键判重: {}", e.getMessage());
            return true;
        }
    }

    private void releaseIdempotentMark(String key) {
        try {
            redisUtil.delete(key);
        } catch (Exception e) {
            // 释放失败最多让客户端在 TTL 内重试被拒，不影响数据正确性
            log.warn("[消息幂等] 释放标记失败: key={}, {}", key, e.getMessage());
        }
    }

    /**
     * 命中幂等标记时回查首次结果。
     *
     * <p>查不到消息说明上一次请求在校验阶段就失败了却没能清理标记，
     * 此时明确报「重复提交」让客户端换 clientMsgId，好过伪造一个成功响应。
     */
    private MessageDTO loadDuplicate(Long fromUserId, String clientMsgId) {
        Message existing = messageMapper.selectByClientMsgId(fromUserId, clientMsgId);
        if (existing == null) {
            log.warn("[消息幂等] 标记存在但消息未落库: fromUserId={}, clientMsgId={}", fromUserId, clientMsgId);
            throw new BusinessException(ResultCode.MESSAGE_DUPLICATE);
        }
        Integer conversationType = conversationSpi.getType(existing.getConversationId());
        return MessageConvert.toDTO(existing, senderOf(existing), conversationType, MessageStatus.SENT,
                loadQuotePreview(existing.getQuoteMsgId()));
    }

    /**
     * 合并群聊 @ 提醒信息。
     *
     * <p>单聊强制清空：@ 在单聊里没有意义，留着会让前端渲染出多余的提醒样式。
     * 群聊则要剔除发送者自己并去重，@ 自己不该产生一条提醒。
     */
    private MessageExtra mergeMention(MessageSendCmd cmd, Integer conversationType) {
        MessageExtra extra = cmd.getExtra();
        if (ConvType.GROUP.getCode() != conversationType) {
            if (extra != null) {
                extra.setAtUserIds(null);
                extra.setAtAll(null);
            }
            return extra;
        }
        boolean atAll = Boolean.TRUE.equals(cmd.getAtAll());
        List<Long> atUserIds = cmd.getAtUserIds() == null ? List.of() : cmd.getAtUserIds().stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(cmd.getFromUserId()))
                .distinct()
                .toList();
        if (!atAll && atUserIds.isEmpty()) {
            return extra;
        }
        if (extra == null) {
            extra = new MessageExtra();
            cmd.setExtra(extra);
        }
        extra.setAtAll(atAll ? Boolean.TRUE : null);
        extra.setAtUserIds(atUserIds.isEmpty() ? null : atUserIds);
        return extra;
    }

    private MessageEvent buildEvent(Message message, Integer conversationType, MessageExtra extra, MessageSendCmd cmd) {
        return MessageEvent.builder()
                .conversationId(message.getConversationId())
                .conversationType(conversationType)
                .messageId(message.getId())
                .seq(message.getSeq())
                .fromUserId(message.getFromUserId())
                .msgType(message.getMsgType())
                // 新消息必然未撤回，摘要不会走到需要昵称的分支，这里省掉一次用户查询
                .summary(MessageConvert.summaryOf(message, null))
                .sendTime(message.getSendTime())
                .receiverIds(receiverIds(message.getConversationId(), message.getFromUserId()))
                .atUserIds(extra == null ? null : extra.getAtUserIds())
                .atAll(extra == null ? null : extra.getAtAll())
                .build();
    }

    /**
     * 双通道推送：接收方收 {@code message}，发送方收 {@code ack}。
     *
     * <p>ack 里带完整消息体并原样回传 clientMsgId，前端一套逻辑就能覆盖两种情形：
     * 本地存在该 clientMsgId 说明是自己刚发的，把「发送中」升级为已发送并回填服务端 ID；
     * 本地不存在说明来自自己的另一台设备，当作新消息追加，多端同步不需要额外协议。
     */
    private void pushNewMessage(MessageDTO dto, Long conversationId, Long fromUserId) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null) {
            return;
        }
        List<Long> receivers = receiverIds(conversationId, fromUserId);
        if (!receivers.isEmpty()) {
            pushSpi.pushToUsers(receivers, fromUserId, WsPacket.of(WsMessageType.MESSAGE, dto));
        }
        pushSpi.pushToUser(fromUserId, WsPacket.of(WsMessageType.ACK, dto.getClientMsgId(), dto));
    }

    /* ==================== 查询 ==================== */

    @Override
    public MessageDTO findDto(Long messageId) {
        Message message = messageMapper.selectById(messageId);
        if (message == null) {
            return null;
        }
        return MessageConvert.toDTO(message, senderOf(message),
                conversationSpi.getType(message.getConversationId()), MessageStatus.SENT,
                loadQuotePreview(message.getQuoteMsgId()));
    }

    @Override
    public boolean fileVisibleTo(Long fileId, Long uploaderId, Long viewerId) {
        if (fileId == null || uploaderId == null || viewerId == null) {
            return false;
        }
        // 上传者看自己的文件不需要查消息表：他可能刚上传完还没把文件发出去，
        // 此时一条引用消息都不存在，但头像预览、重传重试这些场景已经必须能读到它
        if (uploaderId.equals(viewerId)) {
            return true;
        }
        return messageMapper.existsVisibleFileMessage(String.valueOf(fileId), uploaderId, viewerId);
    }

    @Override
    public List<MessageVO> history(Long userId, Long conversationId, Long beforeSeq, int size) {
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                ResultCode.CONVERSATION_NO_PERMISSION);
        List<Message> records = new ArrayList<>(messageMapper.selectHistory(conversationId, beforeSeq, normalizePageSize(size)));
        if (records.isEmpty()) {
            return List.of();
        }
        // 倒序取出的是「游标之前最新的 N 条」，反转回时间正序前端才能直接按顺序渲染
        Collections.reverse(records);
        return toViewList(records, userId);
    }

    @Override
    public PageResult<MessageVO> search(Long userId, MessageSearchQuery query) {
        String keyword = TextUtil.sanitize(query.getKeyword());
        if (TextUtil.isBlank(keyword)) {
            return PageResult.empty(query.safeCurrent(), query.safeSize());
        }
        Long conversationId = query.getConversationId();
        Page<Message> page;
        if (conversationId != null) {
            // 会话内检索：先校验成员身份，再把扫描收敛到单个会话
            BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                    ResultCode.CONVERSATION_NO_PERMISSION);
            page = messageMapper.searchByKeyword(query.toPage(), conversationId, keyword);
        } else {
            // 全局检索：把 LIKE 的扫描范围收敛到「我参与的会话」集合，避免全表模糊查询；
            // 一个会话都没有时直接返回空页，不去打数据库
            List<Long> conversationIds = conversationSpi.listByUser(userId).stream()
                    .map(ConversationBriefDTO::getConversationId)
                    .filter(Objects::nonNull)
                    .toList();
            if (conversationIds.isEmpty()) {
                return PageResult.empty(query.safeCurrent(), query.safeSize());
            }
            page = messageMapper.searchByKeywordInConversations(query.toPage(), conversationIds, keyword);
        }
        // toViewList 会按查看者过滤掉单端删除（im_message_delete）的行，
        // 因此用户清除过的消息不会出现在检索结果里
        List<MessageVO> records = page.getRecords().isEmpty()
                ? List.of()
                : toViewList(new ArrayList<>(page.getRecords()), userId);
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public List<MessageVO> offlineView(Long userId) {
        List<Message> records = loadOffline(userId);
        return records.isEmpty() ? List.of() : toViewList(records, userId);
    }

    @Override
    public List<MessageDTO> offlineDto(Long userId) {
        List<Message> records = loadOffline(userId);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, UserBriefDTO> senders = sendersOf(records);
        Map<Long, Integer> conversationTypes = conversationTypesOf(records);
        Map<Long, QuotePreview> quotes = loadQuotePreviews(records);
        return records.stream()
                .map(message -> MessageConvert.toDTO(message, senders.get(message.getFromUserId()),
                        conversationTypes.get(message.getConversationId()), MessageStatus.SENT,
                        message.getQuoteMsgId() == null ? null : quotes.get(message.getQuoteMsgId())))
                .toList();
    }

    /**
     * 拉取离线消息并推进接收位点。
     *
     * <p>位点取自会话模块的 {@code last_ack_seq}，它只在客户端确认收到后前进，与未读数是两回事：
     * 用户可能已经收到了消息却还没点开看，未读红点必须留着，只有 {@link #markRead} 才清它。
     *
     * <p>位点推进用的是「本次查出的全部消息」的最大 seq，包含被单端删除的那些——
     * 它们同样已经确认过了，下次不该再推一遍。
     */
    private List<Message> loadOffline(Long userId) {
        Map<Long, Long> positions = conversationSpi.getAckPositions(userId);
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        // 位点为空的会话不参与查询，SQL 里的比较需要确定的数值
        Map<Long, Long> safePositions = new LinkedHashMap<>();
        positions.forEach((conversationId, ackSeq) -> {
            if (conversationId != null) {
                safePositions.put(conversationId, ackSeq == null ? 0L : ackSeq);
            }
        });
        if (safePositions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = messageMapper.selectOfflineIds(safePositions, OFFLINE_BATCH_LIMIT);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Message> fetched = new ArrayList<>(messageMapper.selectByIdIn(ids));
        Map<Long, Long> maxSeqByConversation = new HashMap<>();
        for (Message message : fetched) {
            maxSeqByConversation.merge(message.getConversationId(), message.getSeq(), Math::max);
        }
        Set<Long> deletedIds = messageDeleteMapper.selectDeletedIds(userId, ids);
        if (!deletedIds.isEmpty()) {
            fetched.removeIf(message -> deletedIds.contains(message.getId()));
        }
        maxSeqByConversation.forEach((conversationId, maxSeq) ->
                conversationSpi.advanceAck(userId, conversationId, maxSeq));
        return fetched;
    }

    /**
     * 批量组装视图对象。
     *
     * <p>用户资料与回执各查一次：逐条消息查会产生 2N 次数据库往返，
     * 一屏 20 条消息就是 40 次，历史翻页时这个开销会直接体现在响应时间上。
     * 引用预览同样批量加载，避免每条带引用的消息都单独回表。
     */
    private List<MessageVO> toViewList(List<Message> records, Long viewerId) {
        Set<Long> messageIds = records.stream().map(Message::getId).collect(Collectors.toSet());
        Set<Long> deletedIds = messageDeleteMapper.selectDeletedIds(viewerId, messageIds);
        List<Message> visible = deletedIds.isEmpty()
                ? records
                : records.stream().filter(message -> !deletedIds.contains(message.getId())).toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<Long, UserBriefDTO> senders = sendersOf(visible);
        // 回执只对「我发出的消息」有意义，别人的消息在我这边一律显示为已收到，不必查
        Set<Long> selfMessageIds = visible.stream()
                .filter(message -> viewerId.equals(message.getFromUserId()))
                .map(Message::getId)
                .collect(Collectors.toSet());
        // 单聊：逐条回执只有 0/1 行，直接聚合；群聊改由位点推算，im_message_read 里根本没有它们的行
        Map<Long, Receipt> receipts = loadReceipts(selfMessageIds);
        Map<Long, Integer> convTypes = conversationTypesOf(visible);
        // 同一页消息几乎都在同一会话，位点按会话缓存，整页只取一次成员行
        Map<Long, List<MemberPositionDTO>> groupPositions = new HashMap<>();
        // 批量加载引用预览：收集所有 quoteMsgId，一次查出原消息与发送者
        Map<Long, QuotePreview> quotes = loadQuotePreviews(visible);

        return visible.stream().map(message -> {
            boolean self = viewerId.equals(message.getFromUserId());
            Receipt receipt = Receipt.EMPTY;
            if (self) {
                if (isGroupConv(convTypes.get(message.getConversationId()))) {
                    receipt = groupReceipt(message, groupPositions);
                } else {
                    receipt = receipts.getOrDefault(message.getId(), Receipt.EMPTY);
                }
            }
            MessageStatus status = MessageConvert.resolveStatus(message, self, receipt.delivered(), receipt.read());
            QuotePreview quote = message.getQuoteMsgId() == null ? null : quotes.get(message.getQuoteMsgId());
            return MessageConvert.toVO(message, senders.get(message.getFromUserId()), viewerId, status, receipt.read(), quote);
        }).toList();
    }

    /**
     * 群聊消息的送达/已读人数：数成员位点，不数回执行。
     *
     * <p>delivered = 接收位点 ≥ 本条 seq 的成员数，read = 已读位点 ≥ 本条 seq 的成员数，
     * 两者都已排除发送者本人。位点集合每个会话只取一次，页内多条消息复用。
     */
    private Receipt groupReceipt(Message message, Map<Long, List<MemberPositionDTO>> cache) {
        List<MemberPositionDTO> positions = cache.computeIfAbsent(message.getConversationId(),
                conversationId -> conversationSpi.memberPositions(conversationId, message.getFromUserId()));
        long seq = message.getSeq() == null ? Long.MAX_VALUE : message.getSeq();
        int delivered = 0;
        int read = 0;
        for (MemberPositionDTO position : positions) {
            if (position.getAckSeq() != null && position.getAckSeq() >= seq) {
                delivered++;
            }
            if (position.getReadSeq() != null && position.getReadSeq() >= seq) {
                read++;
            }
        }
        return new Receipt(delivered, read);
    }

    /**
     * 按消息 ID 聚合送达数与已读数。
     */
    private Map<Long, Receipt> loadReceipts(Set<Long> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, int[]> counters = new HashMap<>();
        for (MessageRead row : messageReadMapper.selectByMessageIds(messageIds)) {
            int[] counts = counters.computeIfAbsent(row.getMessageId(), key -> new int[2]);
            if (row.getDeliveredTime() != null) {
                counts[0]++;
            }
            if (row.getReadTime() != null) {
                counts[1]++;
            }
        }
        Map<Long, Receipt> receipts = new HashMap<>(counters.size());
        counters.forEach((messageId, counts) -> receipts.put(messageId, new Receipt(counts[0], counts[1])));
        return receipts;
    }

    /**
     * 批量补齐发送者资料，系统通知的发送者 ID 为 0，查不到也不需要查。
     */
    private Map<Long, UserBriefDTO> sendersOf(List<Message> records) {
        Set<Long> senderIds = records.stream()
                .map(Message::getFromUserId)
                .filter(Objects::nonNull)
                .filter(id -> !ImConstants.SYSTEM_USER_ID.equals(id))
                .collect(Collectors.toSet());
        return senderIds.isEmpty() ? Map.of() : userQuerySpi.listByIds(senderIds);
    }

    private Map<Long, Integer> conversationTypesOf(List<Message> records) {
        Set<Long> conversationIds = records.stream()
                .map(Message::getConversationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Integer> types = new HashMap<>(conversationIds.size());
        conversationIds.forEach(conversationId -> types.put(conversationId, conversationSpi.getType(conversationId)));
        return types;
    }

    private UserBriefDTO senderOf(Message message) {
        Long fromUserId = message.getFromUserId();
        if (fromUserId == null || ImConstants.SYSTEM_USER_ID.equals(fromUserId)) {
            return null;
        }
        return userQuerySpi.getById(fromUserId);
    }

    private List<Long> receiverIds(Long conversationId, Long excludeUserId) {
        List<Long> memberIds = conversationSpi.getMemberIds(conversationId);
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        return memberIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(excludeUserId))
                .toList();
    }

    /* ==================== 状态变更 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recall(Long messageId, Long operatorId) {
        Message message = messageMapper.selectById(messageId);
        BusinessException.throwIf(message == null, ResultCode.MESSAGE_NOT_FOUND);
        if (message.isRecalledNow()) {
            // 重复撤回当成功处理：多端同时点撤回或网络重试都会走到这里，报错对客户端毫无意义
            return;
        }
        int limitSeconds = imProperties.getMessage().getRecallLimitSeconds();
        LocalDateTime deadline = message.getSendTime().plusSeconds(limitSeconds);
        BusinessException.throwIf(LocalDateTime.now().isAfter(deadline),
                ResultCode.MESSAGE_RECALL_TIMEOUT);
        validateRecallRight(message, operatorId);

        if (messageMapper.markRecalled(messageId, LocalDateTime.now()) == 0) {
            // 并发撤回：另一个请求已经把它标记掉了，本次同样视作成功
            log.info("[消息撤回] 已被并发撤回: messageId={}", messageId);
            return;
        }
        String summary = MessageConvert.recallSummary(nicknameOf(message.getFromUserId()));
        conversationSpi.onMessageRecalled(message.getConversationId(), messageId, summary);
        afterCommit(() -> pushRecall(message.getConversationId(), messageId, operatorId, summary));
    }

    /**
     * 撤回权限：本人 &gt; 群主 / 管理员 &gt; 持有全局撤回权限者。
     *
     * <p>系统通知不允许撤回：它是各业务流程留下的结果凭证，撤回会让好友关系、群成员变更失去依据。
     */
    private void validateRecallRight(Message message, Long operatorId) {
        if (message.getFromUserId().equals(operatorId)) {
            return;
        }
        BusinessException.throwIf(message.isSystem(), ResultCode.MESSAGE_RECALL_FORBIDDEN);
        Integer conversationType = conversationSpi.getType(message.getConversationId());
        if (conversationType != null && ConvType.GROUP.getCode() == conversationType) {
            GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
            if (groupSpi != null) {
                Long groupId = conversationSpi.getTargetId(message.getConversationId(), operatorId);
                Integer role = groupId == null ? null : groupSpi.getRole(groupId, operatorId);
                if (role != null && GroupRole.of(role).isManager()) {
                    return;
                }
            }
        }
        BusinessException.throwUnless(hasRecallAnyPermission(operatorId), ResultCode.MESSAGE_RECALL_FORBIDDEN);
    }

    /**
     * 是否持有「撤回任意消息」权限，运营后台场景使用。
     *
     * <p>只在当前登录者就是操作者本人时才查 Sa-Token：SPI 入口可能带着别人的 operatorId 进来，
     * 此时上下文里的权限属于当前线程的登录用户，拿它判定 operatorId 的权限是错的。
     */
    private boolean hasRecallAnyPermission(Long operatorId) {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null || !String.valueOf(loginId).equals(String.valueOf(operatorId))) {
                return false;
            }
            return StpUtil.hasPermission(ImConstants.PERM_MESSAGE_RECALL_ANY);
        } catch (Exception e) {
            return false;
        }
    }

    private void pushRecall(Long conversationId, Long messageId, Long operatorId, String summary) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null) {
            return;
        }
        List<Long> memberIds = conversationSpi.getMemberIds(conversationId);
        if (memberIds.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("conversationId", conversationId);
        data.put("operatorId", operatorId);
        data.put("summary", summary);
        // 撤回要对全体成员生效，包括操作者自己的其他设备，因此不排除任何人
        pushSpi.pushToUsers(memberIds, null, WsPacket.of(WsMessageType.RECALL_NOTIFY, data));
    }

    @Override
    public void deleteForUser(Long userId, Long messageId) {
        Message message = messageMapper.selectById(messageId);
        BusinessException.throwIf(message == null, ResultCode.MESSAGE_NOT_FOUND);
        // 只能删自己会话里的消息，否则任意 messageId 都能往删除表灌数据
        BusinessException.throwUnless(conversationSpi.isMember(message.getConversationId(), userId),
                ResultCode.CONVERSATION_NO_PERMISSION);
        if (messageDeleteMapper.exists(userId, messageId)) {
            return;
        }
        MessageDelete record = new MessageDelete();
        record.setId(IdWorker.getId());
        record.setMessageId(messageId);
        record.setUserId(userId);
        record.setCreateTime(LocalDateTime.now());
        try {
            messageDeleteMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.info("[消息删除] 并发重复删除，忽略: userId={}, messageId={}", userId, messageId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearConversationForUser(Long userId, Long conversationId) {
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                ResultCode.CONVERSATION_NO_PERMISSION);
        List<Long> messageIds = messageMapper.selectIdsByConversation(conversationId);
        if (messageIds.isEmpty()) {
            return;
        }
        // 跳过早先已单端删除过的消息，避免撞唯一键 uk_msg_user
        Set<Long> alreadyDeleted = messageDeleteMapper.selectDeletedIds(userId, messageIds);
        LocalDateTime now = LocalDateTime.now();
        List<MessageDelete> batch = new ArrayList<>(CLEAR_BATCH);
        for (Long messageId : messageIds) {
            if (alreadyDeleted.contains(messageId)) {
                continue;
            }
            MessageDelete record = new MessageDelete();
            record.setId(IdWorker.getId());
            record.setMessageId(messageId);
            record.setUserId(userId);
            record.setCreateTime(now);
            batch.add(record);
            if (batch.size() >= CLEAR_BATCH) {
                messageDeleteMapper.insertBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            messageDeleteMapper.insertBatch(batch);
        }
        log.info("[消息删除] 清空会话聊天记录: userId={}, conversationId={}, 消息数={}",
                userId, conversationId, messageIds.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDelivered(Long userId, Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        List<Message> targets = messageMapper.selectByIdIn(new LinkedHashSet<>(messageIds)).stream()
                // 自己发的消息不存在「送达自己」，客户端整屏上报时会混进来
                .filter(message -> !userId.equals(message.getFromUserId()))
                .filter(message -> !message.isRecalledNow())
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        Set<Long> targetIds = targets.stream().map(Message::getId).collect(Collectors.toSet());
        Set<Long> delivered = messageReadMapper.selectByUserAndMessageIds(userId, targetIds).stream()
                .filter(row -> row.getDeliveredTime() != null)
                .map(MessageRead::getMessageId)
                .collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Integer> types = conversationTypesOf(targets);
        List<Message> pending = targets.stream()
                .filter(message -> !delivered.contains(message.getId()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        // 群聊不写逐条回执行：送达人数由成员接收位点推算，写表正是规范警告的表爆炸模式；
        // 推送照旧，发送方正实时看到的气泡靠通知升级状态，重复通知被前端幂等吸收
        List<Message> rows = pending.stream()
                .filter(message -> !isGroupConv(types.get(message.getConversationId())))
                .toList();
        if (!rows.isEmpty()) {
            messageReadMapper.upsertBatch(rows.stream()
                    .map(message -> receiptRow(message.getId(), userId, now, null))
                    .toList());
        }
        afterCommit(() -> pushReceipt(pending, WsMessageType.DELIVERED_NOTIFY, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long conversationId, Long maxSeq) {
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                ResultCode.CONVERSATION_NO_PERMISSION);
        // 不指定位点就按会话当前的最大 seq 全量已读，避免把 null 一路传下去让位点停在原地
        Long ackSeq = maxSeq != null ? maxSeq : messageMapper.selectMaxSeq(conversationId);
        // 未读清零与位点推进是「用户打开了会话」的必然结果，即便回执早已存在也要执行，
        // 否则一次已读上报失败就会让未读红点永久卡住
        if (isGroupConv(conversationSpi.getType(conversationId))) {
            // 群聊：先读旧已读位点再清未读（clearUnread 会把两个位点一起推到 ackSeq），
            // 新变为已读的集合 = 区间 (prevRead, ackSeq]，不写 im_message_read 逐条行
            long prevRead = conversationSpi.readPosition(userId, conversationId);
            conversationSpi.clearUnread(userId, conversationId, ackSeq);
            if (ackSeq <= prevRead) {
                return;
            }
            List<Message> newlyRead = messageMapper.selectReadRange(
                    conversationId, userId, prevRead, ackSeq, MessageMapper.READ_BATCH_LIMIT);
            if (!newlyRead.isEmpty()) {
                afterCommit(() -> pushReceipt(newlyRead, WsMessageType.READ_NOTIFY, userId));
            }
            return;
        }
        // 单聊：每消息最多一行接收记录，不会爆炸，保留逐条回执供发送方查详情
        conversationSpi.clearUnread(userId, conversationId, ackSeq);

        List<Message> pending = messageMapper.selectPendingRead(
                conversationId, userId, ackSeq, MessageMapper.READ_BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 已读隐含已送达，两个时间点一起写；COALESCE 保证不会覆盖更早的真实送达时间
        messageReadMapper.upsertBatch(pending.stream()
                .map(message -> receiptRow(message.getId(), userId, now, now))
                .toList());
        afterCommit(() -> pushReceipt(pending, WsMessageType.READ_NOTIFY, userId));
    }

    /**
     * 会话类型是否为群聊，{@code null}（会话已删或查不到）一律按非群聊处理。
     */
    private static boolean isGroupConv(Integer type) {
        return type != null && ConvType.GROUP.getCode() == type;
    }

    @Override
    public void clearOffline(Long userId) {
        Map<Long, Long> positions = conversationSpi.getAckPositions(userId);
        if (positions == null || positions.isEmpty()) {
            return;
        }
        List<ConversationMaxSeq> rows = messageMapper.selectMaxSeqBatch(positions.keySet());
        for (ConversationMaxSeq row : rows) {
            Long ack = positions.get(row.getConversationId());
            long ackSeq = ack == null ? 0L : ack;
            if (row.getMaxSeq() != null && row.getMaxSeq() > ackSeq) {
                conversationSpi.advanceAck(userId, row.getConversationId(), row.getMaxSeq());
            }
        }
    }

    /**
     * 按发送者分组推送回执：发送方一次收到「哪几条消息被谁读了」，而不是每条一个报文。
     */
    private void pushReceipt(List<Message> messages, WsMessageType type, Long readerId) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null) {
            return;
        }
        Map<Long, List<Long>> grouped = messages.stream()
                .collect(Collectors.groupingBy(Message::getFromUserId,
                        Collectors.mapping(Message::getId, Collectors.toList())));
        grouped.forEach((senderId, ids) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("messageIds", ids);
            data.put("userId", readerId);
            data.put("timestamp", System.currentTimeMillis());
            pushSpi.pushToUser(senderId, WsPacket.of(type, data));
        });
    }

    /**
     * 构造回执行。
     *
     * <p>主键与 create_time 必须手工赋值：{@code im_message_read.id} 不是自增列，
     * 而 MyBatis-Plus 的自动填充只对它自己生成的 insert 语句生效，自定义 SQL 拿不到。
     */
    private MessageRead receiptRow(Long messageId, Long userId, LocalDateTime deliveredTime, LocalDateTime readTime) {
        MessageRead row = new MessageRead();
        row.setId(IdWorker.getId());
        row.setMessageId(messageId);
        row.setUserId(userId);
        row.setDeliveredTime(deliveredTime);
        row.setReadTime(readTime);
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    /* ==================== 引用与转发 ==================== */

    /**
     * 校验引用的原消息。
     *
     * <p>三条规则：原消息必须存在、未撤回、且与新消息属于同一会话。
     * 跨会话引用没有意义（对方看不到原消息），已撤回的消息继续引用等于绕过撤回。
     */
    private void validateQuote(Long quoteMsgId, Long conversationId) {
        if (quoteMsgId == null) {
            return;
        }
        Message quoted = messageMapper.selectById(quoteMsgId);
        BusinessException.throwIf(quoted == null, ResultCode.MESSAGE_QUOTE_INVALID);
        BusinessException.throwIf(quoted.isRecalledNow(), ResultCode.MESSAGE_QUOTE_INVALID);
        BusinessException.throwIf(!quoted.getConversationId().equals(conversationId), ResultCode.MESSAGE_QUOTE_INVALID);
    }

    /**
     * 加载单条引用预览，发送路径与单条查询用。
     */
    private QuotePreview loadQuotePreview(Long quoteMsgId) {
        if (quoteMsgId == null) {
            return null;
        }
        Message quoted = messageMapper.selectById(quoteMsgId);
        if (quoted == null) {
            return null;
        }
        return MessageConvert.toQuotePreview(quoted, senderOf(quoted));
    }

    /**
     * 批量加载引用预览，历史分页与离线拉取用。
     *
     * <p>收集所有非空的 quoteMsgId，一次查出原消息，再批量补齐发送者资料，
     * 避免每条带引用的消息都单独回表。
     */
    private Map<Long, QuotePreview> loadQuotePreviews(List<Message> messages) {
        Set<Long> quoteIds = messages.stream()
                .map(Message::getQuoteMsgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (quoteIds.isEmpty()) {
            return Map.of();
        }
        List<Message> quotedMessages = new ArrayList<>(messageMapper.selectByIdIn(quoteIds));
        if (quotedMessages.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserBriefDTO> quotedSenders = sendersOf(quotedMessages);
        Map<Long, QuotePreview> result = new HashMap<>(quotedMessages.size());
        for (Message quoted : quotedMessages) {
            result.put(quoted.getId(),
                    MessageConvert.toQuotePreview(quoted, quotedSenders.get(quoted.getFromUserId())));
        }
        return result;
    }

    @Override
    public MessageDTO forwardDto(Long userId, ForwardMessageRequest request) {
        Message origin = messageMapper.selectById(request.getMessageId());
        BusinessException.throwIf(origin == null, ResultCode.MESSAGE_NOT_FOUND);
        BusinessException.throwIf(origin.isRecalledNow(), ResultCode.MESSAGE_NOT_FOUND);
        // 转发者必须是原会话成员，否则任何人都能通过猜 messageId 把别人会话里的文件广播出去
        BusinessException.throwUnless(conversationSpi.isMember(origin.getConversationId(), userId),
                ResultCode.MESSAGE_FORWARD_FORBIDDEN);

        String clientMsgId = TextUtil.isNotBlank(request.getClientMsgId())
                ? request.getClientMsgId().trim()
                : "fwd-" + UUID.randomUUID();

        MessageSendCmd cmd = MessageSendCmd.builder()
                .clientMsgId(clientMsgId)
                .conversationId(request.getConversationId())
                .fromUserId(userId)
                .toUserId(request.getToUserId())
                .toGroupId(request.getToGroupId())
                .msgType(origin.getMsgType())
                .content(origin.getContent())
                .extra(origin.getExtra())
                // 转发不携带引用，收到的消息就是一条普通的新消息
                .quoteMsgId(null)
                .forward(Boolean.TRUE)
                .build();
        return send(cmd);
    }

    @Override
    public MessageVO forward(Long userId, ForwardMessageRequest request) {
        return MessageConvert.toVO(forwardDto(userId, request), userId);
    }

    /* ==================== 公共辅助 ==================== */

    private MessageContentHandler handlerOf(MsgType type) {
        MessageContentHandler handler = handlerRegistry.get(type);
        // 缺处理器说明新增了消息类型却忘了实现规范化逻辑，宁可报错也不能让未校验的内容落库
        BusinessException.throwIf(handler == null, ResultCode.MESSAGE_TYPE_UNSUPPORTED);
        return handler;
    }

    private GroupSpi requireGroupSpi() {
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi == null) {
            log.error("[消息] 群组模块未装配，无法处理群聊消息");
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
        return groupSpi;
    }

    /**
     * 撤回文案需要的发送者昵称，查不到时返回 {@code null}，
     * {@link MessageConvert#recallSummary} 会用「对方」兜底。
     */
    private String nicknameOf(Long userId) {
        if (userId == null || ImConstants.SYSTEM_USER_ID.equals(userId)) {
            return null;
        }
        UserBriefDTO sender = userQuerySpi.getById(userId);
        if (sender == null) {
            return null;
        }
        return TextUtil.isNotBlank(sender.getNickname()) ? sender.getNickname() : sender.getUsername();
    }

    private int normalizePageSize(int size) {
        if (size <= 0) {
            return imProperties.getMessage().getHistoryPageSize();
        }
        return (int) Math.min(size, ImConstants.MAX_PAGE_SIZE);
    }

    /**
     * 事务提交后执行副作用，没有活动事务时立即执行。
     *
     * <p>副作用只涉及推送这类「失败了也能靠离线拉取补回来」的动作，
     * 因此异常一律吞掉并记日志，绝不能让推送失败把已经落库的消息回滚掉。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
            return;
        }
        runQuietly(action);
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[消息推送] 推送失败: {}", e.getMessage());
        }
    }

    /**
     * 一条消息对当前查看者的回执统计，只有「我发出的消息」才会被计算。
     */
    private record Receipt(int delivered, int read) {

        private static final Receipt EMPTY = new Receipt(0, 0);
    }
}
