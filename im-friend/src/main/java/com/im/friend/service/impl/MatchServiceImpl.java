package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.domain.DatingCardDTO;
import com.im.common.domain.PageQuery;
import com.im.common.domain.UserBriefDTO;
import com.im.common.domain.WsPacket;
import com.im.common.enums.DatingSort;
import com.im.common.enums.MatchAction;
import com.im.common.enums.MatchSource;
import com.im.common.enums.WsMessageType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.DatingQuerySpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.PushSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.friend.convert.MatchConvert;
import com.im.friend.dto.req.MatchActionRequest;
import com.im.friend.dto.vo.MatchActionVO;
import com.im.friend.dto.vo.MatchLikeVO;
import com.im.friend.dto.vo.MatchPairVO;
import com.im.friend.entity.Friend;
import com.im.friend.entity.MatchExposure;
import com.im.friend.entity.MatchLike;
import com.im.friend.entity.MatchPair;
import com.im.friend.entity.MatchPreference;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.MatchExposureMapper;
import com.im.friend.mapper.MatchLikeMapper;
import com.im.friend.mapper.MatchPairMapper;
import com.im.friend.service.MatchPreferenceService;
import com.im.friend.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 滑动匹配实现。
 *
 * <p>本类只碰 {@code im_match_*} 与 {@code im_friend} 三张表，个人资料一律经
 * {@link DatingQuerySpi} 取：年龄隐藏、距离模糊化、在线位收口这三条规则的实现留在 im-user，
 * 在这里复制一份就会有两套口径，而漏的那一套永远是「多给出去」的那一边。
 *
 * <p>成对后的系统通知与 WebSocket 推送挂在事务提交之后（{@link #afterCommit}）：
 * 两者都不可回滚，在事务内发出就等于「推送说你们成对了，请求回来却发现没成对」。
 * 建会话则<b>必须</b>在事务内且不 catch——理由与 {@code FriendRequestServiceImpl#createConversation}
 * 相同：它自带事务并加入当前事务，吞掉它的异常只会换来一个更难懂的 UnexpectedRollbackException，
 * 而没有会话的配对根本无法聊天，一起失败才是对的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchServiceImpl implements MatchService {

    /** 一次卡池默认给几张：滑动是逐个看的交互，一次给 50 张只是把流量浪费在看不到的卡上 */
    private static final int DECK_DEFAULT_SIZE = 10;
    private static final int DECK_MAX_SIZE = 30;

    /**
     * 互惠名单最多带多少人进池子。
     *
     * <p>这只是一个上限：真正置顶多少个由 im-user 定（它只取卡池的一半），多带的人在那里被截掉。
     * 上限存在的理由是不要把一个「三百人喜欢了你」的热门账号的 ID 全列表带到 SPI 上。
     */
    private static final int BOOST_MAX = 30;
    /** 取「喜欢过我的人」时多取一倍再筛掉已表过态的：他们不该从互惠名额里扣 */
    private static final int BOOST_FETCH = BOOST_MAX * 2;

    /** 成对推送的业务子类型，前端 dispatch 据此刷新会话列表并弹「配对成功」 */
    private static final String BIZ_MATCH_SUCCESS = "match-success";

    private static final String NOTICE_TAIL = "互相喜欢，开始聊天吧";

    private final MatchLikeMapper matchLikeMapper;
    private final MatchPairMapper matchPairMapper;
    private final MatchExposureMapper matchExposureMapper;
    private final FriendMapper friendMapper;
    private final MatchPreferenceService matchPreferenceService;
    private final DatingQuerySpi datingQuerySpi;
    private final UserQuerySpi userQuerySpi;
    private final ObjectProvider<ConversationSpi> conversationSpiProvider;
    private final ObjectProvider<MessageSpi> messageSpiProvider;
    private final ObjectProvider<PushSpi> pushSpiProvider;

    @Override
    public List<DatingCardDTO> deck(Long userId, Integer size, String sort) {
        int limit = size == null ? DECK_DEFAULT_SIZE : Math.max(1, Math.min(size, DECK_MAX_SIZE));
        // 偏好只读一次：它既要变成 SQL 条件（年龄/城市/排序），又要决定排除名单里的「跳过」算不算，
        // 分开读就是同一屏里两套口径，改偏好后刷一次卡会出现「一半新一半旧」
        MatchPreference preference = matchPreferenceService.find(userId);
        DatingSort requested = DatingSort.of(sort);
        Set<Long> excluded = excludedIds(userId, preference);
        // 互惠：喜欢过我、而我还没表过态的人。已配对的会被排除名单挡在池子外，这里先筛一道是为了
        // 不让它们占满那十几个置顶名额，不是为了省一次查询
        List<Long> boostIds = matchLikeMapper.selectLikers(userId, BOOST_FETCH).stream()
                .filter(id -> !excluded.contains(id))
                .limit(BOOST_MAX)
                .toList();
        // 曝光池：七天内推过的人垫后。是垫后不是排除：小池子里两者差别就是「还剩不还剩第二次机会」
        List<Long> exposedIds = matchExposureMapper.selectRecentExposed(userId,
                LocalDateTime.now().minusDays(MatchExposure.REEXPOSE_DAYS),
                MatchExposureMapper.DEPRIORITIZE_MAX);
        DatingCandidateQueryDTO query = DatingCandidateQueryDTO.builder()
                .viewerId(userId)
                .excludeIds(excluded)
                .limit(limit)
                .boostIds(boostIds)
                .deprioritizeIds(exposedIds)
                // 低质号自动降权只有推荐流这一条读法开：他们仍能从「喜欢我的」与「附近的人」里被看到
                .excludeLowQuality(Boolean.TRUE)
                .build();
        if (requested != null) {
            // 本轮显式选的口径直接当成已给定的条件写进去，下面的 applyTo 只填 null，不会覆盖它
            query.setSortBy(requested.getCode());
        }
        // 摊在末尾：applyTo 只填调用方没显式给的字段，所以卡池自己的 limit 不会被偏好覆盖
        matchPreferenceService.applyTo(preference, query);
        List<Long> ids = datingQuerySpi.candidateIds(query);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, DatingCardDTO> cards = datingQuerySpi.cards(ids, userId);
        Set<Long> boosted = new HashSet<>(boostIds);
        Set<Long> exposed = new HashSet<>(exposedIds);
        // 顺序以候选池为准：候选池的排序就是「谁该先被看到」，这里按 map 迭代会把排序排丢
        List<DatingCardDTO> deck = new ArrayList<>(ids.size());
        for (Long id : ids) {
            DatingCardDTO card = cards.get(id);
            if (card != null) {
                card.setMatchReason(matchReason(id, boosted, exposed));
                deck.add(card);
            }
        }
        recordExposure(userId, deck);
        return deck;
    }

    /**
     * 这张卡为什么被推到这里。
     *
     * <p>两个信号都命中时只说「喜欢了你」：「最近见过 TA」不是让人继续滑的理由，互惠才是。
     * 文案在这里写而不是下发两个布尔位，理由写在 {@code DatingCardDTO#matchReason} 上。
     */
    private static String matchReason(Long userId, Set<Long> boosted, Set<Long> exposed) {
        if (boosted.contains(userId)) {
            return "TA 喜欢了你";
        }
        return exposed.contains(userId) ? "最近见过 TA" : null;
    }

    /**
     * 把本次真的交给前端的这几张记成「推给过 TA」。
     *
     * <p>记的是下发的那几条，不是候选池取回的全部：取回而没给出去的人没被任何人看到。
     * 也不等前端上报「用户真的翻到了第 3 张」：那要一个新端点加一套去重，而垫后只需要
     * 「这批人被推过」这个粗信号。
     *
     * <p>失败只记日志：曝光是一个优化项，为了它让一次刷卡报错是最亏的取舍；这一笔丢了的后果
     * 只是下一个人被多推一次。
     */
    private void recordExposure(Long userId, List<DatingCardDTO> deck) {
        if (deck.isEmpty()) {
            // 候选池有人但一张卡也没拼出来（全被封号）：空的 foreach 会拼出一句没有 VALUES 的 INSERT
            return;
        }
        List<MatchExposure> rows = new ArrayList<>(deck.size());
        for (DatingCardDTO card : deck) {
            MatchExposure row = new MatchExposure();
            // 主键必须自己发号：自定义 SQL 拿不到 MyBatis-Plus 的自动填充（同 MessageReadMapper 的约定）
            row.setId(IdWorker.getId());
            row.setUserId(userId);
            row.setCandidateId(card.getUserId());
            rows.add(row);
        }
        try {
            matchExposureMapper.upsertBatch(rows);
        } catch (Exception e) {
            log.warn("[推荐流] 曝光记录写入失败，不影响本次返回: userId={}, size={}, {}",
                    userId, rows.size(), e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MatchActionVO act(Long userId, MatchActionRequest request) {
        MatchAction action = MatchAction.of(request.getAction());
        BusinessException.throwIf(action == null, ResultCode.BAD_REQUEST, "action 只能是 1 喜欢或 2 跳过");
        Long targetId = request.getTargetUserId();
        BusinessException.throwIf(userId.equals(targetId), ResultCode.BAD_REQUEST, "不能对自己滑动");
        MatchSource source = matchSource(request.getSource());
        return action == MatchAction.PASS
                ? pass(userId, targetId, source)
                : like(userId, targetId, source);
    }

    @Override
    public PageResult<MatchLikeVO> likesReceived(Long userId, PageQuery query) {
        Page<MatchLike> page = query.toPage();
        matchLikeMapper.selectReceivedLikes(page, userId);
        List<MatchLike> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }
        List<Long> peerIds = rows.stream().map(MatchLike::getFromUserId).distinct().toList();
        Map<Long, DatingCardDTO> cards = datingQuerySpi.cards(peerIds, userId);
        Set<Long> likedBack = new HashSet<>(matchLikeMapper.selectLikedTargets(userId, peerIds));
        Map<Long, MatchPair> pairs = activePairMap(userId);

        List<MatchLikeVO> items = new ArrayList<>(rows.size());
        for (MatchLike row : rows) {
            DatingCardDTO card = cards.get(row.getFromUserId());
            if (card == null) {
                // 已注销或被封号的一方不出卡片：宁可这一页少一条，也不渲染一张只有昵称空位子的卡
                continue;
            }
            items.add(MatchConvert.toLikeVO(row, card, likedBack.contains(row.getFromUserId()),
                    pairs.get(row.getFromUserId())));
        }
        return PageResult.of(items, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<MatchPairVO> pairs(Long userId, PageQuery query) {
        Page<MatchPair> page = query.toPage();
        matchPairMapper.selectByUserPage(page, userId, MatchPair.STATUS_ACTIVE);
        List<MatchPair> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(page.getCurrent(), page.getSize());
        }
        List<Long> peerIds = rows.stream().map(pair -> pair.peerId(userId)).filter(Objects::nonNull).distinct().toList();
        Map<Long, DatingCardDTO> cards = datingQuerySpi.cards(peerIds, userId);
        List<MatchPairVO> items = rows.stream()
                .map(pair -> MatchConvert.toPairVO(pair, pair.peerId(userId), cards.get(pair.peerId(userId))))
                .toList();
        return PageResult.of(items, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unmatch(Long userId, Long pairId) {
        BusinessException.throwIf(pairId == null, ResultCode.BAD_REQUEST, "配对 ID 不能为空");
        MatchPair pair = matchPairMapper.selectById(pairId);
        // 「不存在」「不是你们俩的配对」「早就解除了」三种情况合成一个码：分开报等于把
        // 「这两个用户成没成过对」变成一个可以被外部查询验证的事实
        BusinessException.throwIf(pair == null || !pair.contains(userId) || !pair.isActive(),
                ResultCode.DATING_PAIR_NOT_FOUND);
        int changed = matchPairMapper.updateStatusIf(pairId, MatchPair.STATUS_ACTIVE, MatchPair.STATUS_UNPAIRED);
        BusinessException.throwIf(changed == 0, ResultCode.DATING_PAIR_NOT_FOUND);
        log.info("[解除配对] user={}, pairId={}, peer={}", userId, pairId, pair.peerId(userId));
    }

    /**
     * 跳过：只改动作，不成对、不校验对方还在不在卡池里。
     *
     * <p>为一件「让 TA 别再回来」的动作去报「对方已经刷不到了」毫无意义——TA 本来就不会再回来。
     * 给一个不存在的人留一行跳过记录没有任何后果，推荐流的排除名单本来就按 ID 过滤。
     */
    private MatchActionVO pass(Long userId, Long targetId, MatchSource source) {
        recordAction(userId, targetId, MatchAction.PASS, source);
        datingQuerySpi.touchActive(userId);
        return MatchConvert.toActionVO(MatchAction.PASS, null, null,
                MatchConvert.actionMessage(MatchAction.PASS, false));
    }

    private MatchActionVO like(Long userId, Long targetId, MatchSource source) {
        // 闸门放在写之前：隐身的人不该被写进任何人的喜欢流水，否则「已喜欢」会成为一个
        // 对方永远看不到的悬挂状态，而它看上去是一次成功操作
        BusinessException.throwIf(!datingQuerySpi.discoverable(targetId, userId), ResultCode.DATING_TARGET_UNAVAILABLE);
        BusinessException.throwIf(blockedEitherWay(userId, targetId), ResultCode.DATING_TARGET_UNAVAILABLE);

        MatchPair existing = matchPairMapper.selectByUsers(userId, targetId);
        if (existing != null && existing.isActive()) {
            // 重复喜欢一个已配对的人：幂等返回成对结果，绝不建第二个会话、也不改对方的来源
            recordAction(userId, targetId, MatchAction.LIKE, source);
            return MatchConvert.toActionVO(MatchAction.LIKE, existing, peerCard(targetId, userId),
                    MatchConvert.actionMessage(MatchAction.LIKE, true));
        }

        recordAction(userId, targetId, MatchAction.LIKE, source);
        MatchLike back = matchLikeMapper.selectByDirection(targetId, userId);
        MatchPair pair = back != null && back.isLike() ? pair(userId, targetId, source) : null;
        datingQuerySpi.touchActive(userId);
        if (pair != null) {
            celebrate(userId, targetId, pair, source);
        }
        return MatchConvert.toActionVO(MatchAction.LIKE, pair,
                pair == null ? null : peerCard(targetId, userId),
                MatchConvert.actionMessage(MatchAction.LIKE, pair != null));
    }

    /**
     * 写滑动动作：一个方向恒为一行，重复滑动改的是这一行。
     *
     * <p>动作与来源都没变时不发 UPDATE：前端重试、用户反复滑同一个人都会走到这里，
     * 而 {@code update_time} 是「谁喜欢我」的排序列，一次空更新会把一条三天前的喜欢顶到最前。
     */
    private void recordAction(Long userId, Long targetId, MatchAction action, MatchSource source) {
        MatchLike existing = matchLikeMapper.selectByDirection(userId, targetId);
        if (existing == null) {
            MatchLike row = new MatchLike();
            row.setFromUserId(userId);
            row.setToUserId(targetId);
            row.setAction(action.getCode());
            row.setSource(source.getCode());
            try {
                matchLikeMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 同一账号在两台设备上同时滑同一个人：唯一键已经有一行，改成这次的动作为止
                updateAction(matchLikeMapper.selectByDirection(userId, targetId), action, source);
            }
            return;
        }
        updateAction(existing, action, source);
    }

    private void updateAction(MatchLike row, MatchAction action, MatchSource source) {
        if (row == null
                || (Objects.equals(row.getAction(), action.getCode())
                && Objects.equals(row.getSource(), source.getCode()))) {
            return;
        }
        MatchLike patch = new MatchLike();
        patch.setId(row.getId());
        patch.setAction(action.getCode());
        patch.setSource(source.getCode());
        matchLikeMapper.updateById(patch);
    }

    /**
     * 成对：建（或复用）会话并落 {@code im_match_pair}。
     *
     * <p>会话 ID <b>每次都走一次幂等的 {@code getOrCreateSingle}</b>，而不是复用行里存的那个：
     * 用户可以在会话列表里把这条会话删掉，存着的 ID 就变成一个指向空气的链接，
     * 推送过去只会让客户端跳进一个查无此会话的页面。
     *
     * <p>并发成对时后写的一方会撞 {@code uk_pair}，转成复用已存在的行；而两边同时建会话时
     * 撞在会话唯一键上的那个请求会整体失败——这是有意的：让其中一次滑动重刷一次即可，
     * 比让两条配对记录指向两个会话要好。
     *
     * <p>两个用户在同一毫秒互点喜欢会双双读不到对方的行，因而都不成对。这个窗口没有在这里加锁
     * 处理：加 {@code FOR UPDATE} 换来的是「成对失败变成一次死锁回滚」，而不成对的后果可以自愈——
     * 双方的「喜欢我的」列表里都有对方，任意一方再点一次喜欢立刻成对。
     */
    private MatchPair pair(Long userId, Long targetId, MatchSource source) {
        Long conversationId = createConversation(userId, targetId);
        MatchPair pair = matchPairMapper.selectByUsers(userId, targetId);
        if (pair == null) {
            MatchPair row = new MatchPair();
            row.setUserAId(MatchPairMapper.lowId(userId, targetId));
            row.setUserBId(MatchPairMapper.highId(userId, targetId));
            row.setConversationId(conversationId);
            row.setStatus(MatchPair.STATUS_ACTIVE);
            row.setLikeSource(source.getCode());
            row.setMsgCount(0);
            try {
                matchPairMapper.insert(row);
                log.info("[交友成对] a={}, b={}, pairId={}, conversationId={}, source={}",
                        row.getUserAId(), row.getUserBId(), row.getId(), conversationId, source.getDesc());
                return row;
            } catch (DuplicateKeyException e) {
                MatchPair concurrent = matchPairMapper.selectByUsers(userId, targetId);
                if (concurrent == null) {
                    // 唯一键冲突却读不到行，只可能是对方在同一瞬间又插又删，按成对失败处理
                    throw new BusinessException(ResultCode.DATING_PAIR_NOT_FOUND);
                }
                return reactivate(concurrent, conversationId, source);
            }
        }
        // 解除过的旧行复用回来：配对时间留在 create_time 上，重新成对不会伪造出一个新的相识时间
        return reactivate(pair, conversationId, source);
    }

    /** 把一行配对置回「配对中」，已经是配对中的行原样返回。 */
    private MatchPair reactivate(MatchPair pair, Long conversationId, MatchSource source) {
        if (pair.isActive()) {
            return pair;
        }
        MatchPair patch = new MatchPair();
        patch.setId(pair.getId());
        patch.setStatus(MatchPair.STATUS_ACTIVE);
        patch.setConversationId(conversationId);
        patch.setLikeSource(source.getCode());
        matchPairMapper.updateById(patch);
        pair.setStatus(MatchPair.STATUS_ACTIVE);
        pair.setConversationId(conversationId);
        pair.setLikeSource(source.getCode());
        return pair;
    }

    /**
     * 成对后的副作用：会话里写一句系统通知，双向各推一条 {@code match-success}。
     *
     * <p>推给两个人而不是只推给后动手的一方：先喜欢的那个人可能正开着应用，
     * 只有一边弹出「配对成功」会让人以为对方那边卡住了。
     */
    private void celebrate(Long actor, Long target, MatchPair pair, MatchSource source) {
        Long conversationId = pair.getConversationId();
        UserBriefDTO self = userQuerySpi.getById(actor);
        UserBriefDTO peer = userQuerySpi.getById(target);
        afterCommit(() -> {
            sendMatchedNotice(conversationId, source);
            PushSpi pushSpi = pushSpiProvider.getIfAvailable();
            if (pushSpi == null) {
                return;
            }
            pushMatched(pushSpi, target, self, pair.getId(), conversationId);
            pushMatched(pushSpi, actor, peer, pair.getId(), conversationId);
        });
    }

    private void sendMatchedNotice(Long conversationId, MatchSource source) {
        MessageSpi spi = messageSpiProvider.getIfAvailable();
        if (conversationId == null || spi == null) {
            return;
        }
        try {
            spi.sendSystemNotice(conversationId, noticeText(source));
        } catch (Exception e) {
            log.warn("[交友成对] 系统通知发送失败: conversationId={}, {}", conversationId, e.getMessage());
        }
    }

    /** 通知文案带上成对的入口：在「附近的人」里成的对写成「推荐流」就是把话编错了。 */
    private static String noticeText(MatchSource source) {
        String prefix = source == MatchSource.RECOMMEND ? "你们" : "你们在「" + source.getDesc() + "」里";
        return prefix + NOTICE_TAIL;
    }

    private void pushMatched(PushSpi pushSpi, Long toUserId, UserBriefDTO peer, Long pairId, Long conversationId) {
        if (toUserId == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bizType", BIZ_MATCH_SUCCESS);
        data.put("userId", peer == null ? null : peer.getUserId());
        data.put("nickname", peer == null ? null : peer.getNickname());
        data.put("avatar", peer == null ? null : peer.getAvatar());
        data.put("pairId", pairId);
        data.put("conversationId", conversationId);
        try {
            pushSpi.pushToUser(toUserId, WsPacket.of(WsMessageType.NOTIFY, data));
        } catch (Exception e) {
            // 推送失败只记日志：成对已经落库，对方上线后仍能从「我的配对」里进会话
            log.debug("[交友成对] 推送失败: userId={}, {}", toUserId, e.getMessage());
        }
    }

    /**
     * 卡池排除名单：已划过的人 + 当前配对中的人 + 我拉黑的人。
     *
     * <p>拉黑只有 im-friend 读得到（{@code im_friend.status=2}），而候选池在 im-user 那边查
     * {@code im_user_profile}，所以只能以 ID 列表的形式把条件传过去，不做跨模块 join。
     *
     * <p>{@code exclude_passed = 0} 时只排除「喜欢过」的人：这一档的语义是「滑过的人可以再遇到」，
     * 而未被回应的喜欢不属于「可以再遇到」——把 TA 再推回来，只会让同一次喜欢被反复重做。
     * 没保存过偏好（{@code preference == null}）时维持原行为：两种都排除。
     */
    private Set<Long> excludedIds(Long userId, MatchPreference preference) {
        MatchAction only = preference != null && !preference.isExcludePassed() ? MatchAction.LIKE : null;
        Set<Long> excluded = new HashSet<>(
                matchLikeMapper.selectSlidTargets(userId, MatchLikeMapper.SLID_EXCLUDE_MAX, only));
        for (MatchPair pair : matchPairMapper.selectActiveByUser(userId)) {
            excluded.add(pair.peerId(userId));
        }
        excluded.addAll(friendMapper.selectBlockedFriendIds(userId));
        return excluded;
    }

    /** 已解除的行不进这个表：解除不等于拉黑，TA 还有可能重新出现在卡池以外的入口里。 */
    private Map<Long, MatchPair> activePairMap(Long userId) {
        Map<Long, MatchPair> map = new HashMap<>();
        for (MatchPair pair : matchPairMapper.selectActiveByUser(userId)) {
            Long peer = pair.peerId(userId);
            if (peer != null) {
                map.put(peer, pair);
            }
        }
        return map;
    }

    /** 任意一方拉黑即拦下这次喜欢：拉黑是「不要再看到我」，比对方的 discoverable 更强。 */
    private boolean blockedEitherWay(Long userId, Long targetId) {
        for (Friend relation : friendMapper.selectBothDirections(userId, targetId)) {
            if (relation.isBlocked()) {
                return true;
            }
        }
        return false;
    }

    private DatingCardDTO peerCard(Long peerId, Long viewerId) {
        return datingQuerySpi.cards(List.of(peerId), viewerId).get(peerId);
    }

    private Long createConversation(Long userA, Long userB) {
        ConversationSpi spi = conversationSpiProvider.getIfAvailable();
        if (spi == null) {
            return null;
        }
        return spi.getOrCreateSingle(userA, userB);
    }

    /** 来源为脏值或未传时一律按推荐流：这是一个可空的业务标记，不是一个必须由用户提供的信息。 */
    private static MatchSource matchSource(Integer code) {
        MatchSource source = MatchSource.of(code);
        return source == null ? MatchSource.RECOMMEND : source;
    }

    /** 把副作用挂到当前事务提交之后执行；无活动事务时立即同步执行。 */
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
