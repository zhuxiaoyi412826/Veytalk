package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.constant.ImConstants;
import com.im.common.constant.RedisKeys;
import com.im.common.domain.MessageDTO;
import com.im.common.domain.MessageExtra;
import com.im.common.domain.MessageSendCmd;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.ConvType;
import com.im.common.enums.MsgType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.ConversationSpi;
import com.im.common.spi.GroupSpi;
import com.im.common.spi.MessageSpi;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.user.dto.po.PacketResidualPO;
import com.im.user.dto.po.PacketStatePO;
import com.im.user.dto.req.PacketSendRequest;
import com.im.user.dto.vo.PacketClaimVO;
import com.im.user.dto.vo.PacketVO;
import com.im.user.dto.vo.WalletActionVO;
import com.im.user.mapper.WalletTransactionMapper;
import com.im.user.service.RedPacketService;
import com.im.user.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 红包实现：钱在 MySQL 账本里，领取态在 Redis Hash 里，两边靠 {@code packetId} 对齐。
 *
 * <p>Redis Hash（{@code RedisKeys#walletPacket}）的字段布局：
 * <ul>
 *   <li>{@code meta}：{@link PacketStatePO} 的 JSON，发出后不再改；</li>
 *   <li>{@code splits}：预切好的每份金额数组（二倍均值法）；</li>
 *   <li>{@code count} / {@code sum}：已领份数与已领金额，靠 {@code HINCRBY} 原子推进；</li>
 *   <li>{@code c:{userId}}：{@code "名额序号:抢到的金额"}，既是「一人一份」的闸门，也是明细的排序依据。</li>
 * </ul>
 *
 * <p><b>为什么先扣钱、后建池子</b>：顺序反过来的话，一旦事务回滚就会留下一个「钱没扣、池子却在」的红包，
 * 谁摸到那个 packetId 就能白领一笔——那是无中生有。现在的顺序里，回滚最多留下一个
 * <b>从没出现在任何消息里、因此无人可寻</b>的孤儿 Hash，TTL 到点自清；
 * 而 Redis 写入失败会让扣款与消息一起回滚，用户那侧只是「红包没发出去」，钱一分没少。
 *
 * <p>因此 {@code send} 里 Redis 写入刻意放在扣款与发消息<b>之后</b>，是本事务的最后一笔副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedPacketServiceImpl implements RedPacketService {

    private static final String FIELD_META = "meta";
    private static final String FIELD_SPLITS = "splits";
    private static final String FIELD_COUNT = "count";
    private static final String FIELD_SUM = "sum";
    private static final String CLAIM_PREFIX = "c:";
    /** 池子比有效期多活 7 天：过期判定读的是 meta.expireAt，多留几天只是给排查留个现场，不影响退款 */
    private static final Duration PACKET_TTL_EXTRA = Duration.ofDays(7);
    /** 退款互斥锁的存活时长：只要盖得住一轮扫描即可，真正的幂等由账本求和负责 */
    private static final Duration REFUND_LOCK_TTL = Duration.ofMinutes(10);

    private final WalletService walletService;
    private final WalletTransactionMapper transactionMapper;
    private final ConversationSpi conversationSpi;
    private final UserQuerySpi userQuerySpi;
    private final MessageSpi messageSpi;
    private final ImProperties imProperties;
    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    /** 群红包要尊重群禁言：internal 发送会跳过消息模块的禁言校验，所以这道闸门必须在钱包侧自己拉起来 */
    private final ObjectProvider<GroupSpi> groupSpiProvider;

    /* ==================== 发 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalletActionVO send(Long userId, PacketSendRequest request) {
        ImProperties.Wallet wallet = requireTransferEnabled();
        Long conversationId = request.getConversationId();
        Integer conversationType = conversationSpi.getType(conversationId);
        BusinessException.throwIf(conversationType == null, ResultCode.CONVERSATION_NOT_FOUND);
        BusinessException.throwUnless(conversationSpi.isMember(conversationId, userId),
                ResultCode.CONVERSATION_NO_PERMISSION);

        boolean group = ConvType.GROUP.getCode() == conversationType;
        int count = group ? resolveGroupCount(request, wallet, conversationId, userId) : 1;
        long amount = request.getAmount();
        BusinessException.throwIf(amount < wallet.getTransferMin() || amount > wallet.getPacketMax(),
                ResultCode.PACKET_AMOUNT_INVALID);
        // 每份至少 1 积分：总额撑不起份数的红包，池子里会凭空多出领到 0 的空名额
        BusinessException.throwIf(amount < count, ResultCode.PACKET_COUNT_INVALID);

        long packetId = IdWorker.getId();
        List<Long> splits = split(amount, count);
        long now = System.currentTimeMillis();
        long expireAt = now + Duration.ofHours(wallet.getPacketExpireHours()).toMillis();

        walletService.debit(userId, amount, ImConstants.WALLET_TX_PACKET, packetId, "发出红包");

        MessageExtra extra = MessageExtra.builder()
                .packetId(packetId)
                .packetAmount(amount)
                .packetCount(count)
                .packetGreeting(request.getGreeting())
                .build();
        MessageDTO message = messageSpi.send(MessageSendCmd.builder()
                .conversationId(conversationId)
                .fromUserId(userId)
                .msgType(MsgType.RED_PACKET.getCode())
                .extra(extra)
                .internal(Boolean.TRUE)
                .build());

        // 本事务最后一步：见类注释「先扣钱、后建池子」
        PacketStatePO meta = PacketStatePO.builder()
                .senderId(userId)
                .conversationId(conversationId)
                .totalAmount(amount)
                .totalCount(count)
                .greeting(message.getExtra() == null ? null : message.getExtra().getPacketGreeting())
                .expireAt(expireAt)
                .build();
        String metaJson = jsonUtil.toJson(meta);
        String splitsJson = jsonUtil.toJson(splits);
        BusinessException.throwIf(metaJson == null || splitsJson == null, ResultCode.SYSTEM_ERROR, "红包状态序列化失败");
        String key = RedisKeys.walletPacket(packetId);
        Map<String, String> hash = new HashMap<>(4);
        hash.put(FIELD_META, metaJson);
        hash.put(FIELD_SPLITS, splitsJson);
        hash.put(FIELD_COUNT, "0");
        hash.put(FIELD_SUM, "0");
        redisUtil.hSetAll(key, hash);
        redisUtil.expire(key, Duration.ofHours(wallet.getPacketExpireHours()).plus(PACKET_TTL_EXTRA));
        log.info("[红包] 发出: packetId={}, senderId={}, conversationId={}, amount={}, count={}",
                packetId, userId, conversationId, amount, count);
        return WalletActionVO.builder()
                .balance(walletService.getBalance(userId))
                .message(message)
                .build();
    }

    /** 群聊份数：为空按 1，超上限夹到 {@code packet-max-count}；顺手挡掉禁言成员在群里发红包 */
    private int resolveGroupCount(PacketSendRequest request, ImProperties.Wallet wallet,
                                  Long conversationId, Long userId) {
        Long groupId = conversationSpi.getTargetId(conversationId, userId);
        GroupSpi groupSpi = groupSpiProvider.getIfAvailable();
        if (groupSpi != null && groupId != null) {
            BusinessException.throwIf(groupSpi.isMuted(groupId, userId), ResultCode.GROUP_MUTED);
        }
        Integer raw = request.getCount();
        int count = raw == null || raw < 1 ? 1 : raw;
        return Math.min(count, wallet.getPacketMaxCount());
    }

    /**
     * 二倍均值预切分：每份在 {@code [1, min(2 * 剩余均值, 剩余额 - 后续每份至少 1)]} 里随机。
     *
     * <p>切好而不领时再算，是为了让「总额 = 各份之和」这件事在任何并发下都成立：
     * 领取现场算金额要么依赖锁、要么依赖浮点余数，而预切分只需要一次 {@code HINCRBY} 拿名额序号。
     * 最后一份直接吃剩余额，避免随机取整后总和对不上。
     */
    private List<Long> split(long amount, int count) {
        List<Long> parts = new ArrayList<>(count);
        long rest = amount;
        int restCount = count;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 1; i < count; i++) {
            long avgDouble = Math.max(1L, rest / restCount * 2);
            long upper = Math.min(avgDouble, rest - (restCount - 1L));
            long piece = upper <= 1L ? 1L : random.nextLong(1L, upper + 1L);
            parts.add(piece);
            rest -= piece;
            restCount--;
        }
        parts.add(rest);
        return parts;
    }

    /* ==================== 抢 ==================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PacketClaimVO claim(Long userId, Long packetId) {
        String key = RedisKeys.walletPacket(packetId);
        PacketStatePO meta = readMeta(key);
        BusinessException.throwIf(meta == null, ResultCode.PACKET_NOT_FOUND);
        // 过了 expireAt 一律拒领：这一刻起这笔差额已经是退款任务眼里的「可退余额」，
        // 再往里放一笔领取，账本求和就会由负转正、从此不再补退，等于凭空多出钱
        BusinessException.throwIf(meta.getExpireAt() == null
                        || System.currentTimeMillis() > meta.getExpireAt(),
                ResultCode.PACKET_NOT_FOUND);
        BusinessException.throwIf(userId.equals(meta.getSenderId()), ResultCode.PACKET_SELF_CLAIM_FORBIDDEN);
        BusinessException.throwUnless(conversationSpi.isMember(meta.getConversationId(), userId),
                ResultCode.PACKET_NOT_MEMBER);
        // 与退款互斥：锁在就说明这一笔正在往回退，此刻放钱进去正好凑成双重支出
        BusinessException.throwIf(redisUtil.hasKey(RedisKeys.walletPacketRefund(packetId)),
                ResultCode.PACKET_NOT_FOUND);

        int totalCount = meta.getTotalCount() == null ? 0 : meta.getTotalCount();
        List<Long> splits = jsonUtil.toList(stringOf(hGet(key, FIELD_SPLITS)), Long.class);
        BusinessException.throwIf(splits.size() < totalCount, ResultCode.PACKET_NOT_FOUND);

        String claimField = CLAIM_PREFIX + userId;
        // HSETNX 是「一人一份」的唯一闸门：先 set 再判存在，两个并发请求会双双通过
        BusinessException.throwUnless(redisUtil.hSetIfAbsent(key, claimField, "1"),
                ResultCode.PACKET_ALREADY_CLAIMED);

        boolean slotTaken = false;
        long seq = 0L;
        long amount = 0L;
        try {
            seq = redisUtil.hIncrement(key, FIELD_COUNT, 1);
            slotTaken = true;
            if (seq > totalCount) {
                // 名额已被别人抢先占走：把这个多出来的计数退回，否则 count 会一路漂在总数之上
                redisUtil.hIncrement(key, FIELD_COUNT, -1);
                redisUtil.hDelete(key, claimField);
                slotTaken = false;
                throw new BusinessException(ResultCode.PACKET_EMPTY);
            }
            amount = splits.get((int) seq - 1);
            redisUtil.hSet(key, claimField, seq + ":" + amount);
            redisUtil.hIncrement(key, FIELD_SUM, amount);
            walletService.credit(userId, amount, ImConstants.WALLET_TX_PACKET, packetId, "领取红包");
            log.info("[红包] 领取: packetId={}, userId={}, seq={}, amount={}", packetId, userId, seq, amount);
            return PacketClaimVO.builder()
                    .packetId(packetId)
                    .amount(amount)
                    .balance(walletService.getBalance(userId))
                    .claimedCount((int) seq)
                    .totalCount(totalCount)
                    .build();
        } catch (RuntimeException e) {
            if (slotTaken) {
                // 数据库回滚了，Redis 的池子状态必须跟着退回去，否则用户会遇到「名额没了、钱也没到」
                redisUtil.hIncrement(key, FIELD_COUNT, -1);
                redisUtil.hIncrement(key, FIELD_SUM, -amount);
                redisUtil.hDelete(key, claimField);
            }
            throw e;
        }
    }

    /* ==================== 详情 ==================== */

    @Override
    public PacketVO detail(Long userId, Long packetId) {
        String key = RedisKeys.walletPacket(packetId);
        PacketStatePO meta = readMeta(key);
        BusinessException.throwIf(meta == null, ResultCode.PACKET_NOT_FOUND);
        Map<Object, Object> hash = redisUtil.hGetAll(key);

        int totalCount = meta.getTotalCount() == null ? 0 : meta.getTotalCount();
        int claimedCount = (int) longOf(hash.get(FIELD_COUNT));
        long claimedAmount = longOf(hash.get(FIELD_SUM));
        // 过期骨架还留着时不报错，只把状态标成 expired：用户点开一个没抢到红包，看到的该是结论而不是弹窗
        boolean expired = meta.getExpireAt() == null || System.currentTimeMillis() > meta.getExpireAt();

        List<PacketVO.Record> records = collectRecords(hash);
        PacketVO.Record mine = null;
        for (PacketVO.Record record : records) {
            if (userId != null && userId.equals(record.getUserId())) {
                mine = record;
                break;
            }
        }
        String status;
        if (expired) {
            status = PacketVO.STATUS_EXPIRED;
        } else if (mine != null) {
            status = PacketVO.STATUS_CLAIMED;
        } else if (claimedCount >= totalCount) {
            status = PacketVO.STATUS_EMPTY;
        } else {
            status = PacketVO.STATUS_UNCLAIMED;
        }
        return PacketVO.builder()
                .packetId(packetId)
                .greeting(meta.getGreeting())
                .totalAmount(meta.getTotalAmount() == null ? 0L : meta.getTotalAmount())
                .totalCount(totalCount)
                .claimedCount(claimedCount)
                .claimedAmount(claimedAmount)
                .status(status)
                .myAmount(mine == null ? 0L : mine.getAmount())
                .expireAt(meta.getExpireAt() == null ? 0L : meta.getExpireAt())
                .records(records)
                .build();
    }

    /** 从 Hash 里挑出 {@code c:*} 字段还原领取明细，按抢到的先后排序并补昵称头像 */
    private List<PacketVO.Record> collectRecords(Map<Object, Object> hash) {
        List<PacketVO.Record> records = new ArrayList<>();
        List<Long> userIds = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : hash.entrySet()) {
            String field = stringOf(entry.getKey());
            if (field == null || !field.startsWith(CLAIM_PREFIX)) {
                continue;
            }
            long claimUserId;
            try {
                claimUserId = Long.parseLong(field.substring(CLAIM_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            // 值形如 "名额:金额"；还是初始占位 "1" 时说明抢到名额但金额尚未写回（并发中），跳过
            String[] parts = stringOf(entry.getValue()).split(":");
            if (parts.length < 2) {
                continue;
            }
            try {
                records.add(PacketVO.Record.builder()
                        .userId(claimUserId)
                        .amount(Long.parseLong(parts[1]))
                        .seq((int) Long.parseLong(parts[0]))
                        .build());
                userIds.add(claimUserId);
            } catch (NumberFormatException e) {
                log.warn("[红包] 领取记录格式异常: field={}, value={}", field, entry.getValue());
            }
        }
        records.sort(Comparator.comparingInt(PacketVO.Record::getSeq));
        if (!userIds.isEmpty()) {
            Map<Long, UserBriefDTO> briefs = userQuerySpi.listByIds(userIds);
            for (PacketVO.Record record : records) {
                UserBriefDTO brief = briefs.get(record.getUserId());
                if (brief != null) {
                    record.setNickname(brief.getNickname());
                    record.setAvatar(brief.getAvatar());
                }
            }
        }
        return records;
    }

    /* ==================== 过期退回 ==================== */

    /**
     * 按账本倒推退款：每个 packetId 下 {@code packet}（负）与 {@code refund}（正）求和，
     * 和为负就是还挂在池子里没人领的钱。
     *
     * <p>刻意<b>不加</b> {@code @Transactional}：每笔退款由 {@code walletService.credit} 自己开一个事务，
     * 一个红包退款失败不该连累同批其余的红包；且这里没有 Redis 状态需要同步回滚
     * （删不掉就交给 TTL，钱已经回到账本上）。
     *
     * <p>也不看开关：{@code transfer-enabled} 关掉的是「新的资金动作」，已经在飞的红包必须能退回去。
     */
    @Override
    public int refundExpired() {
        ImProperties.Wallet wallet = imProperties.getWallet();
        List<PacketResidualPO> rows = transactionMapper.selectExpirablePackets(wallet.getPacketExpireHours());
        if (rows.isEmpty()) {
            return 0;
        }
        int refunded = 0;
        for (PacketResidualPO row : rows) {
            long back = row.getResidual() == null ? 0L : -row.getResidual();
            Long senderId = row.getSenderId();
            if (back <= 0 || senderId == null) {
                continue;
            }
            if (!redisUtil.setIfAbsent(RedisKeys.walletPacketRefund(row.getPacketId()), "1", REFUND_LOCK_TTL)) {
                continue;
            }
            try {
                walletService.credit(senderId, back, ImConstants.WALLET_TX_PACKET_REFUND, row.getPacketId(), "红包过期退回");
                redisUtil.delete(RedisKeys.walletPacket(row.getPacketId()));
                refunded++;
                log.info("[红包] 过期退回: packetId={}, senderId={}, amount={}", row.getPacketId(), senderId, back);
            } catch (Exception e) {
                // 不吞掉也不中断：残额还在账本上，下一轮扫描会再来一次，不需要人工兜底
                log.error("[红包] 过期退回失败: packetId={}, senderId={}", row.getPacketId(), senderId, e);
            }
        }
        return refunded;
    }

    /* ==================== 内部工具 ==================== */

    private ImProperties.Wallet requireTransferEnabled() {
        ImProperties.Wallet wallet = imProperties.getWallet();
        BusinessException.throwUnless(wallet.isEnabled() && wallet.isTransferEnabled(), ResultCode.WALLET_DISABLED);
        return wallet;
    }

    /** 读红包骨架：Redis 里的坏值一律按「这个红包不存在」处理，不能让一个脏字段把客户端打成 500 */
    private PacketStatePO readMeta(String key) {
        String metaJson = stringOf(hGet(key, FIELD_META));
        return jsonUtil.fromJsonQuietly(metaJson, PacketStatePO.class);
    }

    private Object hGet(String key, String field) {
        return redisUtil.hGet(key, field);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
