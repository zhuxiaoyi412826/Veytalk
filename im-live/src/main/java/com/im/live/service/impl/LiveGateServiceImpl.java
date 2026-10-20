package com.im.live.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.constant.RedisKeys;
import com.im.common.exception.BusinessException;
import com.im.common.spi.WalletSpi;
import com.im.common.util.JsonUtil;
import com.im.common.util.RedisUtil;
import com.im.live.dto.po.LiveGatePO;
import com.im.live.dto.vo.LiveGateVO;
import com.im.live.service.LiveGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 门禁实现：配置写 {@code im:live:gate:{roomId}}，解锁态写 {@code im:live:unlock:{roomId}:{userId}}，全 Redis、零表。
 *
 * <p>见 {@link LiveGateService} 类注释里的「闸门在哪」「已知边界」。这里只补存储细节：
 * 门禁键 TTL 用 {@link #GATE_TTL}（默认 12 小时）并靠开播/心跳 {@link #touch} 续期；解锁键一次性写入、
 * 同 TTL。选 12 小时与 {@code im.live.replay.max-duration-hours} 同量级——一场直播撑死几小时，
 * 12 小时足够覆盖「直播 + 观众中途离场再回来」，又不会在主播早已停播后把门禁态留在 Redis 里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveGateServiceImpl implements LiveGateService {

    private static final Duration GATE_TTL = Duration.ofHours(12);

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final WalletSpi walletSpi;

    @Override
    public void seed(Long roomId, Long anchorId, String password, Long price) {
        // 先清一次：开播复用不到旧键（roomId 每次新建），但 abort/重开竞态下留个干净起点，
        // 也让「这场不设门禁」明确落到无键状态
        redisUtil.delete(RedisKeys.liveGate(roomId));
        boolean hasPassword = password != null && !password.isBlank();
        boolean hasPrice = price != null && price > 0;
        if (!hasPassword && !hasPrice) {
            return;
        }
        LiveGatePO.LiveGatePOBuilder builder = LiveGatePO.builder().roomId(roomId).anchorId(anchorId);
        if (hasPassword) {
            // 密码与价同时给时按密码优先（price 被忽略），与 DTO 约定一致
            builder.mode(LiveGatePO.MODE_PASSWORD).pwdHash(sha256Hex(password.trim()));
        } else {
            builder.mode(LiveGatePO.MODE_PAID).price(price);
        }
        redisUtil.set(RedisKeys.liveGate(roomId), jsonUtil.toJson(builder.build()), GATE_TTL);
        log.info("开播设门禁: roomId={}, anchorId={}, mode={}", roomId, anchorId,
                hasPassword ? LiveGatePO.MODE_PASSWORD : LiveGatePO.MODE_PAID);
    }

    @Override
    public void touch(Long roomId) {
        String key = RedisKeys.liveGate(roomId);
        if (redisUtil.hasKey(key)) {
            redisUtil.expire(key, GATE_TTL);
        }
    }

    @Override
    public LiveGatePO configOf(Long roomId) {
        if (roomId == null) {
            return null;
        }
        return jsonUtil.fromJsonQuietly(redisUtil.get(RedisKeys.liveGate(roomId)), LiveGatePO.class);
    }

    @Override
    public boolean isUnlocked(Long roomId, Long userId) {
        return roomId != null && userId != null
                && redisUtil.hasKey(RedisKeys.liveUnlock(roomId, userId));
    }

    @Override
    public boolean isLockedFor(Long roomId, Long userId) {
        return configOf(roomId) != null && !isUnlocked(roomId, userId);
    }

    @Override
    public void unlockByPassword(Long roomId, Long userId, String password) {
        LiveGatePO po = configOf(roomId);
        BusinessException.throwIf(po == null || !LiveGatePO.MODE_PASSWORD.equals(po.getMode()),
                ResultCode.LIVE_GATE_NOT_PASSWORD);
        if (password == null || !sha256Hex(password.trim()).equals(po.getPwdHash())) {
            throw new BusinessException(ResultCode.LIVE_GATE_PASSWORD_WRONG);
        }
        markUnlocked(roomId, userId);
        log.info("密码解锁成功: roomId={}, userId={}", roomId, userId);
    }

    @Override
    public void unlockByPay(Long roomId, Long userId) {
        // 已解锁直接返回：付费房重进不该二次扣款，也覆盖前端重复点击/竞态
        if (isUnlocked(roomId, userId)) {
            return;
        }
        LiveGatePO po = configOf(roomId);
        BusinessException.throwIf(po == null || !LiveGatePO.MODE_PAID.equals(po.getMode()),
                ResultCode.LIVE_GATE_NOT_PAID);
        long price = po.getPrice() == null ? 0L : po.getPrice();
        if (userId != null && userId.equals(po.getAnchorId())) {
            // 主播进自己的房恒免：不扣自己那份象征性的入场费
            markUnlocked(roomId, userId);
            return;
        }
        BusinessException.throwUnless(walletSpi.enabled(), ResultCode.WALLET_DISABLED);
        BusinessException.throwIf(price <= 0, ResultCode.LIVE_GATE_PRICE_INVALID);
        // 扣款与解锁不成同一事务（解锁是 Redis 写）：先扣钱、扣成功才种标记。
        // 反过来「先解锁后扣钱」会出现没付钱就能看；扣款失败（余额不足）抛异常、不写标记。
        walletSpi.deduct(userId, price, ImConstants.WALLET_TX_LIVE_PAY, roomId, "付费直播入场");
        markUnlocked(roomId, userId);
        log.info("付费解锁成功: roomId={}, userId={}, price={}", roomId, userId, price);
    }

    @Override
    public void drop(Long roomId) {
        redisUtil.delete(RedisKeys.liveGate(roomId));
    }

    @Override
    public LiveGateVO describe(LiveGatePO po) {
        LiveGateVO vo = new LiveGateVO();
        if (po == null) {
            vo.setType(LiveGateVO.TYPE_NONE);
            return vo;
        }
        if (LiveGatePO.MODE_PAID.equals(po.getMode())) {
            vo.setType(LiveGateVO.TYPE_PAID);
            vo.setPrice(po.getPrice());
        } else {
            vo.setType(LiveGateVO.TYPE_PASSWORD);
        }
        return vo;
    }

    /* ==================== 内部 ==================== */

    private void markUnlocked(Long roomId, Long userId) {
        redisUtil.set(RedisKeys.liveUnlock(roomId, userId), "1", GATE_TTL);
    }

    /**
     * SHA-256 十六进制小写摘要。JDK 强制提供 SHA-256，{@code NoSuchAlgorithmException} 不可达，
     * 真抛了就是运行时环境坏了，包成 {@code SYSTEM_ERROR} 而不是吞掉——密码摘要算不出来绝不能退回明文比较。
     */
    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR);
        }
    }
}
