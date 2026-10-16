package com.im.live.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.GiftBriefDTO;
import com.im.common.domain.UserBriefDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.spi.WalletSpi;
import com.im.common.util.RedisUtil;
import com.im.live.entity.GiftRecord;
import com.im.live.entity.LiveRoom;
import com.im.live.mapper.GiftRecordMapper;
import com.im.live.mapper.LiveRoomMapper;
import com.im.live.service.GiftService;
import com.im.live.service.LiveDanmakuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 送礼服务实现。
 *
 * <p>钱、账、热度三段写必须在同一事务里原子生效：扣款走 {@link WalletSpi#deduct}（内部条件 UPDATE + 账本流水），
 * 再写一条 {@code im_gift_record}，最后累加本场礼物热度。任何一步失败整笔回滚——不会出现「扣了钱没送礼记录」
 * 或「礼物飘屏了却没扣钱」。单体同库同源，{@code WalletSpi} 的 {@code REQUIRED} 事务并入本方法事务。
 *
 * <p>飘屏广播刻意放到事务提交之后：广播是「让当下在线的人看到特效」的易失动作，落库失败不该飘屏，
 * 而广播失败也绝不该把一笔已经成功的送礼回滚掉（仿 {@code MessageServiceImpl} 的 {@code afterCommit} 约定）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftServiceImpl implements GiftService {

    /** 热度键的兜底 TTL：房间结束后这份聚合不再有意义，留一天足够后续读取，又不会永久占用 */
    private static final Duration GIFT_HEAT_TTL = Duration.ofHours(24);

    private final LiveRoomMapper roomMapper;
    private final GiftRecordMapper giftRecordMapper;
    private final WalletSpi walletSpi;
    private final UserQuerySpi userQuerySpi;
    private final LiveDanmakuService danmakuService;
    private final ImProperties imProperties;
    private final RedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sendGift(Long userId, Long roomId, Long giftId, int count) {
        ImProperties.Wallet wallet = imProperties.getWallet();
        BusinessException.throwUnless(walletSpi.enabled(), ResultCode.WALLET_DISABLED);

        int maxCount = Math.max(1, wallet.getMaxGiftCount());
        BusinessException.throwIf(count < 1 || count > maxCount, ResultCode.GIFT_AMOUNT_INVALID);

        LiveRoom room = roomMapper.selectById(roomId);
        BusinessException.throwIf(room == null, ResultCode.LIVE_ROOM_NOT_FOUND);
        BusinessException.throwUnless(room.isLiving(), ResultCode.LIVE_NOT_LIVING);

        GiftBriefDTO gift = walletSpi.findGift(giftId);
        // findGift 只对「不存在或已下架」返回 null；前端礼簿只渲染上架礼物，走到 null 基本是构造请求，统一按不存在拒
        BusinessException.throwIf(gift == null, ResultCode.GIFT_NOT_FOUND);

        int unitPrice = gift.getPrice() == null ? 0 : gift.getPrice();
        long totalPrice = (long) unitPrice * count;

        // 扣款：余额不足时 WalletSpi 抛 WALLET_BALANCE_NOT_ENOUGH，整笔回滚
        walletSpi.deduct(userId, totalPrice, ImConstants.WALLET_TX_GIFT, roomId, "送礼-" + gift.getName());

        GiftRecord record = new GiftRecord();
        record.setSenderId(userId);
        record.setReceiverId(room.getAnchorId());
        record.setRoomId(roomId);
        record.setGiftId(gift.getGiftId());
        record.setGiftName(gift.getName());
        record.setGiftIcon(gift.getIcon());
        record.setCount(count);
        record.setUnitPrice(unitPrice);
        record.setTotalPrice(totalPrice);
        giftRecordMapper.insert(record);

        // 本场累计礼物热度：按成交价值累加，供后续连麦 PK / 热度榜取用
        String heatKey = RedisKeys.liveGiftHeat(roomId);
        redisUtil.increment(heatKey, totalPrice);
        redisUtil.expire(heatKey, GIFT_HEAT_TTL);

        UserBriefDTO sender = userQuerySpi.getById(userId);
        String senderName = sender == null ? null : sender.getNickname();
        String senderAvatar = sender == null ? null : sender.getAvatar();
        Long receiverId = room.getAnchorId();
        afterCommit(() -> danmakuService.broadcastGift(roomId, gift.getGiftId(), gift.getName(), gift.getIcon(),
                gift.getKind(), count, totalPrice, userId, senderName, senderAvatar, receiverId));

        Map<String, Object> result = new LinkedHashMap<>(8);
        result.put("balance", walletSpi.getBalance(userId));
        result.put("giftName", gift.getName());
        result.put("giftIcon", gift.getIcon());
        result.put("count", count);
        result.put("totalPrice", totalPrice);
        log.info("送礼成功: roomId={}, senderId={}, giftId={}, count={}, totalPrice={}",
                roomId, userId, giftId, count, totalPrice);
        return result;
    }

    /** 事务提交后执行广播；无活跃事务时立即执行。失败只记日志，不影响已提交的送礼。 */
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
            log.warn("[送礼广播] 飘屏下发失败: {}", e.getMessage());
        }
    }
}
