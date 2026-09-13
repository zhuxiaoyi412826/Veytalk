package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.RequestStatus;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.friend.convert.FriendConvert;
import com.im.friend.dto.req.FriendApplyRequest;
import com.im.friend.dto.req.FriendRequestQuery;
import com.im.friend.dto.vo.FriendRequestVO;
import com.im.friend.entity.Friend;
import com.im.friend.entity.FriendRequest;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.FriendRequestMapper;
import com.im.friend.service.FriendRequestService;
import com.im.friend.service.FriendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好友申请服务实现。
 *
 * <p>跨模块协作全部通过 {@link ObjectProvider} 弱引用：im-conversation / im-message / im-websocket
 * 未装配时相关副作用静默跳过，好友关系本身仍能正确建立，保证模块可独立启动与测试。
 *
 * <p>WebSocket 推送一律挂到事务提交之后（见 {@link #afterCommit}）：推送是不可回滚的外部副作用，
 * 若在事务内发出，一旦回滚客户端会收到「已成为好友」却查不到关系的不一致状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendRequestServiceImpl implements FriendRequestService {

    /** 同意好友后写入会话的第一条系统通知文案 */
    private static final String NOTICE_ACCEPTED = "你们已经是好友了，开始聊天吧";

    /** NOTIFY 报文的业务子类型，前端据此决定是刷新申请列表还是弹提示 */
    private static final String BIZ_FRIEND_REQUEST = "friend-request";
    private static final String BIZ_FRIEND_ACCEPTED = "friend-accepted";

    private final FriendRequestMapper friendRequestMapper;
    private final FriendMapper friendMapper;
    private final FriendService friendService;
    private final UserQuerySpi userQuerySpi;
    private final ObjectProvider<ConversationSpi> conversationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;
    private final ObjectProvider<PushSpi> pushSpiProvider;

    @Override
    public FriendRequestVO apply(Long userId, FriendApplyRequest request) {
        Long targetId = resolveTarget(request);
        BusinessException.throwIf(targetId.equals(userId), ResultCode.FRIEND_APPLY_SELF);

        UserBriefDTO target = userQuerySpi.getById(targetId);
        BusinessException.throwIf(target == null, ResultCode.USER_NOT_FOUND);

        checkRelationAbsent(userId, targetId);

        BusinessException.throwIf(friendRequestMapper.selectPending(userId, targetId) != null,
                ResultCode.FRIEND_APPLY_DUPLICATE);

        FriendRequest entity = new FriendRequest();
        entity.setFromUserId(userId);
        entity.setToUserId(targetId);
        entity.setVerifyMessage(TextUtil.isBlank(request.getVerifyMessage())
                ? null : TextUtil.sanitize(request.getVerifyMessage().trim(), 255));
        entity.setSource(TextUtil.isBlank(request.getSource())
                ? FriendRequest.SOURCE_SEARCH : request.getSource().trim());
        entity.setStatus(RequestStatus.PENDING.getCode());
        try {
            friendRequestMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发重复提交时由 uk_from_to_pending 兜底，转成友好提示而不是 500
            log.warn("[好友申请] 命中唯一键，视为重复申请: from={}, to={}", userId, targetId);
            throw new BusinessException(ResultCode.FRIEND_APPLY_DUPLICATE);
        }

        UserBriefDTO self = userQuerySpi.getById(userId);
        notifyApplied(self, target, entity);
        log.info("[好友申请] from={}, to={}, requestId={}", userId, targetId, entity.getId());
        // 站在「我发出的」视角回显，peer 即目标用户
        return FriendConvert.toVO(entity, target, false);
    }

    @Override
    public PageResult<FriendRequestVO> listReceived(Long userId, FriendRequestQuery query) {
        return pageByDirection(userId, query, true);
    }

    @Override
    public PageResult<FriendRequestVO> listSent(Long userId, FriendRequestQuery query) {
        return pageByDirection(userId, query, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long accept(Long userId, Long requestId) {
        FriendRequest request = requirePendingForHandler(userId, requestId);
        Long fromUserId = request.getFromUserId();

        // 先原子抢占处理权：并发同意时只有一个请求能把状态从 PENDING 改走
        BusinessException.throwIf(markHandled(request.getId(), RequestStatus.ACCEPTED) == 0,
                ResultCode.FRIEND_APPLY_NOT_FOUND);

        friendService.bindRelation(fromUserId, userId);
        Long conversationId = createConversation(fromUserId, userId);

        UserBriefDTO self = userQuerySpi.getById(userId);
        // 系统通知与推送都挂到提交之后：两者都会走带事务的 SPI，在事务内 catch 它们的异常是无效的
        // （原因见 createConversation 注释）；放到提交后，下面的 catch 才真的能兜住失败。
        // 通知先于推送：客户端收到「已成为好友」后立刻拉历史，才能看到这条系统消息。
        afterCommit(() -> {
            sendAcceptedNotice(conversationId);
            notifyAccepted(self, fromUserId, conversationId);
        });
        log.info("[同意好友申请] handler={}, applicant={}, requestId={}, conversationId={}",
                userId, fromUserId, requestId, conversationId);
        return conversationId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long userId, Long requestId) {
        FriendRequest request = requirePendingForHandler(userId, requestId);
        BusinessException.throwIf(markHandled(request.getId(), RequestStatus.REJECTED) == 0,
                ResultCode.FRIEND_APPLY_NOT_FOUND);
        log.info("[拒绝好友申请] handler={}, applicant={}, requestId={}", userId, request.getFromUserId(), requestId);
    }

    @Override
    public long countPending(Long userId) {
        return userId == null ? 0L : friendRequestMapper.countPending(userId);
    }

    /**
     * 解析申请目标：优先用 targetUserId，否则按账号或手机号反查。
     */
    private Long resolveTarget(FriendApplyRequest request) {
        if (request.getTargetUserId() != null) {
            return request.getTargetUserId();
        }
        String account = request.getTargetAccount();
        BusinessException.throwIf(TextUtil.isBlank(account),
                ResultCode.BAD_REQUEST, "targetUserId 与 targetAccount 至少填写一个");
        UserBriefDTO target = userQuerySpi.findByAccount(account.trim());
        BusinessException.throwIf(target == null, ResultCode.USER_NOT_FOUND);
        return target.getUserId();
    }

    /**
     * 申请前置关系校验：已是好友、被对方拉黑、我已拉黑对方三种情况分别给出独立错误码。
     */
    private void checkRelationAbsent(Long userId, Long targetId) {
        List<Friend> both = friendMapper.selectBothDirections(userId, targetId);
        if (both.isEmpty()) {
            return;
        }
        boolean normalPair = both.size() == 2 && both.stream().noneMatch(Friend::isBlocked);
        BusinessException.throwIf(normalPair, ResultCode.FRIEND_ALREADY_EXISTS);

        for (Friend relation : both) {
            if (!relation.isBlocked()) {
                continue;
            }
            // 拉黑行的持有者是对方 -> 我被拉黑；持有者是我 -> 需先自己解除拉黑
            if (targetId.equals(relation.getUserId())) {
                throw new BusinessException(ResultCode.FRIEND_BLOCKED);
            }
            throw new BusinessException(ResultCode.FRIEND_BLOCKED_BY_ME);
        }
    }

    /**
     * 收到的 / 发出的申请共用同一套分页逻辑，仅查询列与「对方」的取值方向不同。
     */
    private PageResult<FriendRequestVO> pageByDirection(Long userId, FriendRequestQuery query, boolean received) {
        Page<FriendRequest> page = query.toPage();
        LambdaQueryWrapper<FriendRequest> wrapper = Wrappers.<FriendRequest>lambdaQuery()
                .eq(received ? FriendRequest::getToUserId : FriendRequest::getFromUserId, userId)
                .eq(query.getStatus() != null, FriendRequest::getStatus, query.getStatus())
                .orderByDesc(FriendRequest::getCreateTime)
                .orderByDesc(FriendRequest::getId);
        friendRequestMapper.selectPage(page, wrapper);

        List<FriendRequest> records = page.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }
        List<Long> peerIds = records.stream().map(item -> peerIdOf(item, received)).distinct().toList();
        Map<Long, UserBriefDTO> peers = userQuerySpi.listByIds(peerIds);
        List<FriendRequestVO> items = records.stream()
                .map(item -> FriendConvert.toVO(item, peers.get(peerIdOf(item, received)), received))
                .toList();
        return PageResult.of(items, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private Long peerIdOf(FriendRequest request, boolean received) {
        return received ? request.getFromUserId() : request.getToUserId();
    }

    /**
     * 取出待处理申请并校验处理人身份。
     *
     * <p>「不存在」「不属于我」「已被处理」统一返回同一错误码，避免被用来探测他人申请是否存在。
     */
    private FriendRequest requirePendingForHandler(Long userId, Long requestId) {
        BusinessException.throwIf(requestId == null, ResultCode.BAD_REQUEST, "申请 ID 不能为空");
        FriendRequest request = friendRequestMapper.selectPendingForHandler(requestId, userId);
        BusinessException.throwIf(request == null, ResultCode.FRIEND_APPLY_NOT_FOUND);
        return request;
    }

    /**
     * 带状态条件的更新，兼作乐观锁。
     *
     * @return 影响行数，0 表示已被其它请求抢先处理
     */
    private int markHandled(Long requestId, RequestStatus status) {
        return friendRequestMapper.update(null, Wrappers.<FriendRequest>lambdaUpdate()
                .set(FriendRequest::getStatus, status.getCode())
                .set(FriendRequest::getHandleTime, LocalDateTime.now())
                .eq(FriendRequest::getId, requestId)
                .eq(FriendRequest::getStatus, RequestStatus.PENDING.getCode()));
    }

    /**
     * 建好友时创建单聊会话。
     *
     * <p>这里不 catch 异常：{@code getOrCreateSingle} 自带 {@code @Transactional} 并加入当前事务，
     * 一旦它抛异常，事务已被标记 rollback-only，吞掉异常只会让提交时抛出一个更难懂的
     * {@code UnexpectedRollbackException}，好友关系照样一起回滚——所谓「降级保存好友关系」是假的。
     * 而没有会话的好友关系本来也没法聊天，一起失败更合理。im-group 的
     * {@code createGroupConversation} 用的是同一套处理方式。
     */
    private Long createConversation(Long userA, Long userB) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null) {
            return null;
        }
        return spi.getOrCreateSingle(userA, userB);
    }

    /**
     * 发一条「你们已经是好友了」的系统通知，失败只记日志。
     *
     * <p>必须在事务提交之后调用（见 {@link #accept}）：{@code sendSystemNotice} 最终走
     * {@code MessageServiceImpl.send}，同样自带事务，在事务内 catch 它是兜不住的。
     */
    private void sendAcceptedNotice(Long conversationId) {
        MessageSpi spi = messageSpiProvider.getIfAvailable();
        if (conversationId == null || spi == null) {
            return;
        }
        try {
            spi.sendSystemNotice(conversationId, NOTICE_ACCEPTED);
        } catch (Exception e) {
            log.warn("[好友申请] 发送系统通知失败: conversationId={}, {}", conversationId, e.getMessage());
        }
    }

    /**
     * 推送「收到好友申请」给被申请人，让前端立刻亮起红点而无需轮询。
     */
    private void notifyApplied(UserBriefDTO self, UserBriefDTO target, FriendRequest entity) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null || target == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizType", BIZ_FRIEND_REQUEST);
        data.put("requestId", entity.getId());
        data.put("fromUserId", entity.getFromUserId());
        data.put("fromNickname", displayName(self));
        data.put("fromAvatar", self == null ? null : self.getAvatar());
        data.put("verifyMessage", entity.getVerifyMessage());
        pushQuietly(pushSpi, target.getUserId(), data);
    }

    /**
     * 推送「对方已同意」给申请人，前端可据此直接打开新会话。
     */
    private void notifyAccepted(UserBriefDTO self, Long applicantId, Long conversationId) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizType", BIZ_FRIEND_ACCEPTED);
        data.put("userId", self == null ? null : self.getUserId());
        data.put("nickname", displayName(self));
        data.put("avatar", self == null ? null : self.getAvatar());
        data.put("conversationId", conversationId);
        pushQuietly(pushSpi, applicantId, data);
    }

    /**
     * 推送失败只记 debug 日志：目标不在线是常态，不能影响主流程。
     */
    private void pushQuietly(PushSpi pushSpi, Long userId, Map<String, Object> data) {
        try {
            pushSpi.pushToUser(userId, WsPacket.of(WsMessageType.NOTIFY, data));
        } catch (Exception e) {
            log.debug("[好友申请] 推送通知失败: userId={}, {}", userId, e.getMessage());
        }
    }

    private String displayName(UserBriefDTO user) {
        if (user == null) {
            return null;
        }
        return TextUtil.isNotBlank(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    /**
     * 把副作用挂到当前事务提交之后执行；无活动事务时立即同步执行。
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }
}
