package com.im.user.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminGiftDTO;
import com.im.common.domain.AdminWalletDTO;
import com.im.common.domain.AdminWalletOverviewDTO;
import com.im.common.domain.AdminWalletQueryDTO;
import com.im.common.domain.AdminWalletTxDTO;
import com.im.common.domain.AdminWalletTxQueryDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminWalletSpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.Gift;
import com.im.user.entity.User;
import com.im.user.entity.UserWallet;
import com.im.user.entity.WalletTransaction;
import com.im.user.mapper.GiftMapper;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserWalletMapper;
import com.im.user.mapper.WalletTransactionMapper;
import com.im.user.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台钱包与礼物目录的实现，读 {@code im_user_wallet} / {@code im_wallet_transaction} / {@code im_gift}。
 *
 * <p>钱的变动一律不在这个类里写 SQL：{@link #adjust} 把入账与扣款都交回
 * {@link WalletService}，也就是用户侧充值/送礼用的同一具身体（条件 UPDATE + 同事务补流水）。
 * 在这里重抄一遍「UPDATE balance = balance + ?」能少写十行，代价是账本与余额出现两套口径，
 * 而这两套口径迟早要在对账时相互指认对方是错的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletAdminSpiImpl implements AdminWalletSpi {

    /** 台账与流水的单页上限，与视频 / 直播 / 远程控制后台同口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 全站流水检索的单页上限，比带 userId 的更严，理由见 {@code AdminWalletTxQueryDTO} 的类注释 */
    private static final long MAX_GLOBAL_TX_PAGE_SIZE = 50;
    private static final int KEYWORD_MAX = 30;
    /** 昵称反查用户 ID 的行数上限：撞名的人再多，也只为台账取最近注册的这些 */
    private static final int USER_NAME_HIT_LIMIT = 500;
    /** 档案里带几条最近流水，见 {@code AdminWalletDTO#recentTransactions} 的字段注释 */
    private static final int RECENT_TX = 10;
    /** {@code im_wallet_transaction.remark} 的列宽，超出的事由被截掉而不是让插入报错 */
    private static final int REMARK_MAX = 128;
    /** 昵称占位：用户注销后钱包行与流水都还在，那一行仍然要能显示出来 */
    private static final String DELETED_NICKNAME = "已注销用户";

    private final UserWalletMapper walletMapper;
    private final WalletTransactionMapper transactionMapper;
    private final GiftMapper giftMapper;
    private final UserMapper userMapper;
    private final WalletService walletService;

    @Override
    public AdminWalletOverviewDTO overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // 三个 SUM 一次拿全：这张表除主键外没有索引，扫一遍和扫三遍是同一个代价
        Map<String, Object> walletTotals = firstMap(walletMapper.selectMaps(Wrappers.<UserWallet>query()
                .select("COUNT(*) AS accountCount",
                        "COALESCE(SUM(balance), 0) AS totalBalance",
                        "COALESCE(SUM(total_recharged), 0) AS totalRecharged",
                        "COALESCE(SUM(total_consumed), 0) AS totalConsumed")));
        // 今日三个数字同一条聚合：条数、净额、其中后台调整了几笔
        Map<String, Object> todayTotals = firstMap(transactionMapper.selectMaps(Wrappers.<WalletTransaction>query()
                .select("COUNT(*) AS txCount",
                        "COALESCE(SUM(amount), 0) AS txAmount",
                        "COALESCE(SUM(CASE WHEN type = '" + ImConstants.WALLET_TX_ADJUST
                                + "' THEN 1 ELSE 0 END), 0) AS adjustCount")
                .ge("create_time", todayStart)));

        return AdminWalletOverviewDTO.builder()
                .walletEnabled(walletService.enabled())
                .accountCount(asLong(walletTotals.get("accountCount")))
                .totalBalance(asLong(walletTotals.get("totalBalance")))
                .totalRecharged(asLong(walletTotals.get("totalRecharged")))
                .totalConsumed(asLong(walletTotals.get("totalConsumed")))
                .todayTxCount(asLong(todayTotals.get("txCount")))
                .todayTxAmount(asLong(todayTotals.get("txAmount")))
                .todayAdjustCount(asLong(todayTotals.get("adjustCount")))
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Override
    public PageResult<AdminWalletDTO> pageWallets(AdminWalletQueryDTO query) {
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()), MAX_PAGE_SIZE);

        LambdaQueryWrapper<UserWallet> wrapper = Wrappers.<UserWallet>lambdaQuery()
                .eq(query.getUserId() != null, UserWallet::getUserId, query.getUserId())
                .ge(query.getMinBalance() != null, UserWallet::getBalance, query.getMinBalance());

        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);
        if (keyword != null) {
            // 昵称在 im_user 上、钱在 im_user_wallet 上，所以关键词要先变成一批 ID。
            // 数字另算一条：管理员手上的往往是那个 ID 本身，不是花名。
            List<Long> nameHits = userIdsByNameLike(keyword);
            Long asId = parseLongOrNull(keyword);
            wrapper.and(w -> {
                boolean opened = false;
                if (asId != null) {
                    w.eq(UserWallet::getUserId, asId);
                    opened = true;
                }
                if (!nameHits.isEmpty()) {
                    if (opened) {
                        w.or();
                    }
                    w.in(UserWallet::getUserId, nameHits);
                    opened = true;
                }
                if (!opened) {
                    // 一个都没命中：给个恒不成立的条件下推，别让整页退化成「无过滤」
                    w.eq(UserWallet::getUserId, -1L);
                }
            });
        }

        // 固定「余额高的在前」，不接受排序参数：理由写在 AdminWalletQueryDTO 的类注释里
        wrapper.orderByDesc(UserWallet::getBalance).orderByAsc(UserWallet::getUserId);

        Page<UserWallet> page = walletMapper.selectPage(new Page<>(current, size), wrapper);
        List<UserWallet> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, String> names = nicknamesOf(rows.stream().map(UserWallet::getUserId).toList());
        List<AdminWalletDTO> records = new ArrayList<>(rows.size());
        for (UserWallet row : rows) {
            records.add(toView(row, names.get(row.getUserId()), null));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public AdminWalletDTO detail(Long userId) {
        if (userId == null) {
            return null;
        }
        UserWallet row = walletMapper.selectById(userId);
        if (row == null) {
            // 没开过钱包的号在这里就是「无此行」，不惰性建行：后台读一个账户不该往库里写东西
            return null;
        }
        return toView(row, nicknameOf(userId), recentTransactions(userId, RECENT_TX));
    }

    @Override
    public PageResult<AdminWalletTxDTO> pageTransactions(AdminWalletTxQueryDTO query) {
        boolean global = query.getUserId() == null;
        long current = Math.max(1, query.getCurrent());
        long size = Math.min(Math.max(1, query.getSize()),
                global ? MAX_GLOBAL_TX_PAGE_SIZE : MAX_PAGE_SIZE);

        LambdaQueryWrapper<WalletTransaction> wrapper = Wrappers.<WalletTransaction>lambdaQuery()
                .eq(!global, WalletTransaction::getUserId, query.getUserId())
                .eq(!TextUtil.isBlank(query.getType()), WalletTransaction::getType, trimTo(query.getType(), 16))
                .lt(Boolean.TRUE.equals(query.getMinusOnly()), WalletTransaction::getAmount, 0L)
                .ge(query.getStartTime() != null, WalletTransaction::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, WalletTransaction::getCreateTime, query.getEndTime());
        String keyword = trimTo(query.getKeyword(), KEYWORD_MAX);
        wrapper.like(keyword != null, WalletTransaction::getRemark, keyword);
        // 雪花 ID 随时间递增，倒着走主键就能边扫边停；按 create_time 排要把命中行全捞出来 filesort
        wrapper.orderByDesc(WalletTransaction::getId);

        Page<WalletTransaction> page = transactionMapper.selectPage(new Page<>(current, size), wrapper);
        List<WalletTransaction> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(current, size);
        }
        Map<Long, String> names = nicknamesOf(rows.stream().map(WalletTransaction::getUserId).distinct().toList());
        List<AdminWalletTxDTO> records = new ArrayList<>(rows.size());
        for (WalletTransaction tx : rows) {
            records.add(toTxView(tx, names.get(tx.getUserId())));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminWalletDTO adjust(Long userId, long delta, String remark, Long adminId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "userId 不能为空");
        BusinessException.throwIf(delta == 0, ResultCode.BAD_REQUEST, "调整额不能为 0（要不变就别提交）");
        BusinessException.throwIf(!walletService.enabled(), ResultCode.WALLET_DISABLED);
        String composed = buildRemark(remark, adminId);

        // 正数入账、负数扣减，两条路都走 WalletService：那里有 balance >= ? 的兜底
        // 和「同事务补一条带 balance_after 的流水」，本方法只负责事由与操作人
        if (delta > 0) {
            walletService.credit(userId, delta, ImConstants.WALLET_TX_ADJUST, null, composed);
        } else {
            walletService.debit(userId, -delta, ImConstants.WALLET_TX_ADJUST, null, composed);
        }
        log.info("[后台调账] userId={}, delta={}, 操作人={}, 事由={}", userId, delta, adminId, composed);
        return detail(userId);
    }

    @Override
    public List<AdminGiftDTO> giftCatalog() {
        List<Gift> gifts = giftMapper.selectList(Wrappers.<Gift>lambdaQuery()
                .orderByAsc(Gift::getSort)
                .orderByAsc(Gift::getId));
        List<AdminGiftDTO> result = new ArrayList<>(gifts.size());
        for (Gift gift : gifts) {
            result.add(toGiftView(gift));
        }
        return result;
    }

    @Override
    public AdminGiftDTO setGiftStatus(Long giftId, boolean onShelf) {
        BusinessException.throwIf(giftId == null, ResultCode.BAD_REQUEST, "giftId 不能为空");
        Gift gift = giftMapper.selectById(giftId);
        if (gift == null) {
            return null;
        }
        int status = onShelf ? Gift.STATUS_ON : Gift.STATUS_OFF;
        if (!Integer.valueOf(status).equals(gift.getStatus())) {
            giftMapper.update(null, Wrappers.<Gift>lambdaUpdate()
                    .set(Gift::getStatus, status)
                    .eq(Gift::getId, giftId));
            log.info("[礼物{}] giftId={}, name={}", onShelf ? "上架" : "下架", giftId, gift.getName());
        }
        return toGiftView(giftMapper.selectById(giftId));
    }

    /* ==================== 内部工具 ==================== */

    /** 档案装配：{@code recent} 为空即列表行 */
    private AdminWalletDTO toView(UserWallet row, String nickname, List<AdminWalletTxDTO> recent) {
        return AdminWalletDTO.builder()
                .userId(row.getUserId())
                .nickname(nickname == null ? DELETED_NICKNAME : nickname)
                .balance(row.getBalance() == null ? 0L : row.getBalance())
                .totalRecharged(row.getTotalRecharged() == null ? 0L : row.getTotalRecharged())
                .totalConsumed(row.getTotalConsumed() == null ? 0L : row.getTotalConsumed())
                .signLastDate(row.getSignLastDate())
                .createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime())
                .recentTransactions(recent)
                .build();
    }

    private AdminWalletTxDTO toTxView(WalletTransaction tx, String nickname) {
        return AdminWalletTxDTO.builder()
                .txId(tx.getId())
                .userId(tx.getUserId())
                .nickname(nickname == null ? DELETED_NICKNAME : nickname)
                .type(tx.getType())
                .amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter())
                .refId(tx.getRefId())
                .remark(tx.getRemark())
                .createTime(tx.getCreateTime())
                .build();
    }

    private AdminGiftDTO toGiftView(Gift gift) {
        return AdminGiftDTO.builder()
                .giftId(gift.getId())
                .name(gift.getName())
                .icon(gift.getIcon())
                .price(gift.getPrice())
                .kind(gift.getKind())
                .sort(gift.getSort())
                .status(gift.getStatus())
                .createTime(gift.getCreateTime())
                .updateTime(gift.getUpdateTime())
                .build();
    }

    private List<AdminWalletTxDTO> recentTransactions(Long userId, int limit) {
        Page<WalletTransaction> page = transactionMapper.selectPage(new Page<>(1, limit),
                Wrappers.<WalletTransaction>lambdaQuery()
                        .eq(WalletTransaction::getUserId, userId)
                        .orderByDesc(WalletTransaction::getId));
        List<WalletTransaction> rows = page.getRecords();
        if (rows.isEmpty()) {
            return List.of();
        }
        String name = nicknameOf(userId);
        List<AdminWalletTxDTO> result = new ArrayList<>(rows.size());
        for (WalletTransaction tx : rows) {
            result.add(toTxView(tx, name));
        }
        return result;
    }

    private String nicknameOf(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : displayOf(user);
    }

    private Map<Long, String> nicknamesOf(Collection<Long> userIds) {
        return nicknameMap(new ArrayList<>(userIds));
    }

    private Map<Long, String> nicknameMap(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(userIds.size());
        for (User user : userMapper.selectByIds(userIds)) {
            result.put(user.getId(), displayOf(user));
        }
        return result;
    }

    /** 展示名用昵称，昵称空着退到用户名——和登录页那套显示口径保持一致 */
    private static String displayOf(User user) {
        return TextUtil.isBlank(user.getNickname()) ? user.getUsername() : user.getNickname();
    }

    private List<Long> userIdsByNameLike(String keyword) {
        Page<User> page = userMapper.selectPage(new Page<>(1, USER_NAME_HIT_LIMIT),
                Wrappers.<User>lambdaQuery()
                        .select(User::getId)
                        .like(User::getNickname, keyword)
                        .orderByDesc(User::getId));
        List<Long> ids = new ArrayList<>(page.getRecords().size());
        for (User user : page.getRecords()) {
            ids.add(user.getId());
        }
        return ids;
    }

    /** 事由拼成「事由（后台：操作人昵称 #ID）」，长度夹到列宽，超出的截掉而不是让插入报错 */
    private String buildRemark(String remark, Long adminId) {
        String reason = trimTo(remark, 80);
        BusinessException.throwIf(reason == null, ResultCode.BAD_REQUEST, "调账事由必填");
        String operator = "未知";
        if (adminId != null) {
            String name = nicknameOf(adminId);
            operator = (name == null ? DELETED_NICKNAME : name) + " #" + adminId;
        }
        String composed = reason + "（后台：" + operator + "）";
        return composed.length() > REMARK_MAX ? composed.substring(0, REMARK_MAX) : composed;
    }

    private static Map<String, Object> firstMap(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** MySQL 的 SUM 回来是 BigDecimal，COUNT 是 Long，统一收成 long */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
