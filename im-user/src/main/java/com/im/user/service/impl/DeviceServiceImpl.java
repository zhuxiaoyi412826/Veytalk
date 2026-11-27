package com.im.user.service.impl;

import cn.dev33.satoken.session.SaTerminalInfo;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.config.ImProperties;
import com.im.common.enums.DeviceType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.PushSpi;
import com.im.common.util.DeviceIds;
import com.im.common.util.SecurityUtil;
import com.im.common.util.TextUtil;
import com.im.user.dto.vo.LoginDeviceVO;
import com.im.user.dto.vo.LoginRecordVO;
import com.im.user.entity.LoginLog;
import com.im.user.entity.UserDevice;
import com.im.user.mapper.LoginLogMapper;
import com.im.user.mapper.UserDeviceMapper;
import com.im.user.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link DeviceService} 实现。
 *
 * <h2>顶号为什么按「设备位去重」而不是按会话条数</h2>
 *
 * <p>Sa-Token 自带的 {@code maxLoginCount} 数的是 terminal 条数，而同一台浏览器可能因为多次刷新
 * 留下好几条 terminal——用它就会出现「用户没多登录一台机器，却被告知超限」。所以本类先把终端
 * 按 {@code 类型:实例} 归组，<b>一个位算一台</b>，再与 {@code im.account.device-limit.*} 比大小。
 *
 * <h2>为什么淘汰时不能顺手把老客户端一起踢了</h2>
 *
 * <p>已发布的 APK 与小程序只上送裸类型码，它的设备位就等于类型码：一个账号装过两台手机、
 * 上报的都是 {@code android}，后端分不清是几台。此时顶号必然误伤（两台互相挤下线），
 * 于是 {@link DeviceIds#isLegacy} 为 true 的位既<b>不被淘汰</b>，也<b>不计入台数</b>——
 * 它连「占一个位」的资格都没有，否则两台老客户端就能把新客户端挤出去。
 *
 * <h2>同一实例的旧 token 只做静默注销</h2>
 *
 * <p>同一台机器重复登录会留下旧 token（旧标签页还拿着它）。注销它是对的，但<b>不推 kickout
 * 报文、不关连接</b>，并且走 {@code logoutByTokenValue} 而不是 {@code kickoutByTokenValue}：
 * 后者会把该 token 标成「被顶下线」，没关的那个页签下一次请求就会弹一句「账号已在其他设备登录」——
 * 而它本来就是同一台机器，这就是自我误伤。跨实例顶号才需要 kickout 语义与打扰用户。
 *
 * <h2>流水里的 {@code ip} / {@code device_name} 描的是谁</h2>
 *
 * <p>统一规则：<b>描「事件涉及的那台设备」</b>。登录 / 注销行就是本机自己；顶号与撤销行是被踢的那台
 * （取它登记册上的最近 IP），因为用户看这条记录时要回答的是「这台我不认识的机器什么时候上过线」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    /** 一轮流水清理最多删多少行：这是后台任务，宁可多跑几轮也不长占连接 */
    private static final int PURGE_BATCH = 500;
    /** 登录记录一屏封顶，与配置项取小 */
    private static final int RECORD_MAX = 50;
    /** 被踢报文里的时间格式：到分钟就够，精确到秒只会让人怀疑自己看错 */
    private static final DateTimeFormatter KICK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** UA 里的浏览器特征，★ 顺序即优先级：Edge / Opera 的 UA 同时含 Chrome 与 Safari 字样 */
    private static final String[][] UA_BROWSER = {
            {"Edg/", "Edge"}, {"OPR/", "Opera"}, {"Chrome/", "Chrome"}, {"Firefox/", "Firefox"}, {"Safari/", "Safari"}};
    /** UA 里的操作系统特征 */
    private static final String[][] UA_OS = {
            {"Windows NT", "Windows"}, {"Mac OS X", "macOS"}, {"Macintosh", "macOS"},
            {"HarmonyOS", "鸿蒙"}, {"Android", "安卓"}, {"iPhone", "iOS"}, {"iPad", "iOS"}, {"Linux", "Linux"}};

    private final UserDeviceMapper userDeviceMapper;
    private final LoginLogMapper loginLogMapper;
    private final ImProperties imProperties;
    private final OnlineStatusSpi onlineStatusSpi;
    /** 顶号要推 kickout 报文；im-websocket 未装配时只注销会话，会话断了客户端自然回登录页 */
    private final ObjectProvider<PushSpi> pushSpiProvider;

    @Override
    public void onLogin(Long userId, String tokenValue, String deviceSlot, String ip,
                        String userAgent, String deviceName) {
        if (userId == null) {
            return;
        }
        String slot = DeviceIds.normalize(deviceSlot);
        String type = DeviceIds.typeOf(slot);
        String instance = DeviceIds.instanceOf(slot);
        LocalDateTime now = LocalDateTime.now();
        String shown = resolveName(deviceName, userAgent, type);

        upsertDevice(userId, type, instance, shown, ip, userAgent, now);
        writeLog(userId, LoginLog.EVENT_LOGIN, type, instance, shown, ip, userAgent, null);

        // 同实例的旧 token：静默换掉，不惊动任何页签
        retireSameSlotTokens(userId, slot, tokenValue);
        // 跨实例超额顶号：先推报文再注销，因此整段在 evictOverLimit 内部完成
        evictOverLimit(userId, slot, tokenValue, shown, ip, now);
        // ★ 注销旧会话会触发监听器清在线键（解析不到设备位时它会清掉全部终端），必须补回本机
        onlineStatusSpi.setOnline(userId, slot);
    }

    @Override
    public void recordEvent(Long userId, String deviceSlot, int event, String detail) {
        if (userId == null) {
            return;
        }
        try {
            // 空设备位 = 这件事不归属某一台设备（如未登录态下重置密码）：此时
            // 不能把它 normalize 成 web，否则流水里会多出一条「从浏览器登录」的假记录
            if (TextUtil.isBlank(deviceSlot)) {
                writeLog(userId, event, "", null, null, SecurityUtil.getClientIp(),
                        SecurityUtil.getUserAgent(), detail);
                return;
            }
            String slot = DeviceIds.normalize(deviceSlot);
            String type = DeviceIds.typeOf(slot);
            writeLog(userId, event, type, DeviceIds.instanceOf(slot),
                    resolveName(SecurityUtil.getDeviceName(), SecurityUtil.getUserAgent(), type),
                    SecurityUtil.getClientIp(), SecurityUtil.getUserAgent(), detail);
        } catch (Exception e) {
            // 留痕失败绝不能把登录 / 改密 / 绑定这些主动作带失败
            log.warn("[设备留痕] 写入失败: userId={}, event={}, err={}", userId, event, e.getMessage());
        }
    }

    @Override
    public List<LoginDeviceVO> listByUser(Long userId) {
        if (userId == null) {
            return List.of();
        }
        int max = Math.min(RECORD_MAX, Math.max(1, imProperties.getAccount().getDeviceListMax()));
        List<UserDevice> rows = userDeviceMapper.selectList(Wrappers.<UserDevice>lambdaQuery()
                .eq(UserDevice::getUserId, userId)
                .orderByDesc(UserDevice::getLastLoginTime)
                .orderByDesc(UserDevice::getId)
                .last("LIMIT " + max));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> online = new HashSet<>(onlineStatusSpi.getOnlineDevices(userId));
        String current = SecurityUtil.getLoginDevice();
        List<LoginDeviceVO> list = new ArrayList<>(rows.size());
        for (UserDevice row : rows) {
            String slot = DeviceIds.slot(row.getDeviceType(), row.getDeviceId());
            boolean identifiable = !TextUtil.isBlank(row.getDeviceId());
            list.add(LoginDeviceVO.builder()
                    .id(row.getId())
                    .deviceType(row.getDeviceType())
                    .deviceTypeDesc(DeviceType.of(row.getDeviceType()).getDesc())
                    .deviceName(resolveName(row.getDeviceName(), row.getUserAgent(), row.getDeviceType()))
                    .ip(row.getIp())
                    .loginCount(row.getLoginCount())
                    .lastLoginTime(row.getLastLoginTime())
                    .lastLogoutTime(row.getLastLogoutTime())
                    .status(row.getStatus())
                    .current(slot.equals(current))
                    .online(online.contains(slot))
                    // 不能单独定位 = 撤销它会把同类型的所有老客户端会话一起下线，前端须提前讲明
                    .identifiable(identifiable)
                    .build());
        }
        return list;
    }

    @Override
    public List<LoginRecordVO> listRecords(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        int size = Math.min(RECORD_MAX, Math.max(1, limit));
        // 雪花 ID 即时间序，按主键倒序就是按发生时间倒序，与 im_moderation_log 同一口径
        List<LoginLog> rows = loginLogMapper.selectList(Wrappers.<LoginLog>lambdaQuery()
                .eq(LoginLog::getUserId, userId)
                .orderByDesc(LoginLog::getId)
                .last("LIMIT " + size));
        List<LoginRecordVO> list = new ArrayList<>(rows.size());
        for (LoginLog row : rows) {
            list.add(LoginRecordVO.builder()
                    .id(row.getId())
                    .event(row.getEvent())
                    .eventDesc(eventDesc(row.getEvent()))
                    .deviceType(row.getDeviceType())
                    .deviceTypeDesc(DeviceType.of(row.getDeviceType()).getDesc())
                    .deviceName(resolveName(row.getDeviceName(), row.getUserAgent(), row.getDeviceType()))
                    .ip(row.getIp())
                    .detail(row.getDetail())
                    .createTime(row.getCreateTime())
                    .build());
        }
        return list;
    }

    @Override
    public boolean revoke(Long userId, Long id) {
        if (userId == null || id == null) {
            return false;
        }
        // 双条件定位：只按主键查就能撤销别人的设备
        UserDevice row = userDeviceMapper.selectOne(Wrappers.<UserDevice>lambdaQuery()
                .eq(UserDevice::getId, id)
                .eq(UserDevice::getUserId, userId));
        if (row == null) {
            return false;
        }
        String slot = DeviceIds.slot(row.getDeviceType(), row.getDeviceId());
        BusinessException.throwIf(slot.equals(SecurityUtil.getLoginDevice()), ResultCode.BAD_REQUEST,
                "不能撤销当前正在使用的这台设备");
        int kicked = killSlot(userId, slot, "已在「登录设备」管理页被下线", null, LoginLog.EVENT_REVOKED,
                "登录设备管理页撤销了该设备");
        userDeviceMapper.update(null, Wrappers.<UserDevice>lambdaUpdate()
                .eq(UserDevice::getId, row.getId())
                .set(UserDevice::getStatus, UserDevice.STATUS_REVOKED)
                .set(UserDevice::getLastLogoutTime, LocalDateTime.now()));
        // 撤销 legacy 位时监听器可能整键清掉在线状态，补回本机
        String current = SecurityUtil.getLoginDevice();
        if (current != null) {
            onlineStatusSpi.setOnline(userId, current);
        }
        log.info("[设备管理] 撤销设备: userId={}, slot={}, 影响会话={}", userId, slot, kicked);
        return true;
    }

    @Override
    public int logoutOthers(Long userId, String reason, int event) {
        if (userId == null) {
            return 0;
        }
        String current = SecurityUtil.getLoginDevice();
        Set<String> slots = new HashSet<>();
        // 这里必须看全部类型：「下线其他设备」是跨端的，只清当前端等于没清
        for (SaTerminalInfo terminal : safeTerminals(userId, null)) {
            String slot = DeviceIds.slot(terminal.getDeviceType(), terminal.getDeviceId());
            if (!slot.equals(current)) {
                slots.add(slot);
            }
        }
        int count = 0;
        for (String slot : slots) {
            count += killSlot(userId, slot, reason, null, event, reason);
        }
        if (current != null) {
            onlineStatusSpi.setOnline(userId, current);
        }
        log.info("[设备管理] 下线其他设备: userId={}, event={}, 设备数={}", userId, event, count);
        return count;
    }

    @Override
    public int purgeExpiredLogs() {
        int keepDays = imProperties.getAccount().getLoginLogKeepDays();
        // 0 = 永不清理：这张表会随登录量单调增长，是配置里明确写下的取舍
        if (keepDays <= 0) {
            return 0;
        }
        return loginLogMapper.deleteExpired(LocalDateTime.now().minusDays(keepDays), PURGE_BATCH);
    }

    /* ==================== 顶号 ==================== */

    /**
     * 同一设备位上的旧 token 静默注销（不推报文、不关连接）。
     */
    private void retireSameSlotTokens(Long userId, String slot, String tokenValue) {
        if (DeviceIds.isLegacy(slot)) {
            return;
        }
        for (SaTerminalInfo terminal : safeTerminals(userId, DeviceIds.typeOf(slot))) {
            if (slot.equals(DeviceIds.slot(terminal.getDeviceType(), terminal.getDeviceId()))
                    && !Objects.equals(terminal.getTokenValue(), tokenValue)) {
                endSession(terminal.getTokenValue(), false);
            }
        }
    }

    /**
     * 按「同端最多 N 台」淘汰超额设备位，最久没动的先下。
     *
     * <p>台数排序键用终端里<b>最早</b>那条 token 的创建时间：同一个位上的多个 token 是同一台机器的
     * 多次登录，取最早值相当于「这台机器已经在线多久」，把最久的那台挤掉最接近用户直觉。
     * 不去读 {@code im_user_device.last_login_time} 是因为那需要多一次查库，而续签（{@code refresh}）
     * 会刷新它，反而可能让一台早已离开的机器看起来最新。
     */
    private void evictOverLimit(Long userId, String slot, String tokenValue,
                                String newDeviceName, String ip, LocalDateTime now) {
        ImProperties.Account account = imProperties.getAccount();
        if (!account.isExclusiveLogin() || DeviceIds.isLegacy(slot)) {
            return;
        }
        int limit = account.limitOf(DeviceIds.typeOf(slot));
        Map<String, Long> oldest = new LinkedHashMap<>(8);
        Map<String, List<String>> tokens = new LinkedHashMap<>(8);
        for (SaTerminalInfo terminal : safeTerminals(userId, DeviceIds.typeOf(slot))) {
            String other = DeviceIds.slot(terminal.getDeviceType(), terminal.getDeviceId());
            // 排除本机：它刚登录，必然要留下；排除 legacy：它分不清是几台
            if (other.equals(slot) || DeviceIds.isLegacy(other)) {
                continue;
            }
            tokens.computeIfAbsent(other, k -> new ArrayList<>()).add(terminal.getTokenValue());
            oldest.merge(other, terminal.getCreateTime(), Math::min);
        }
        // 本机也要占一个位，所以判定式里 +1
        int need = tokens.size() + 1 - limit;
        if (need <= 0) {
            return;
        }
        List<String> victims = new ArrayList<>(oldest.keySet());
        victims.sort(Comparator.comparingLong(oldest::get));
        String time = KICK_TIME.format(now);
        for (int i = 0; i < need && i < victims.size(); i++) {
            String victim = victims.get(i);
            // reason 只说「发生了什么」，「是谁 / 何时 / 何地」全交给 detail：三端客户端都会把 detail
            // 拼到 reason 后面，这里再写一遍设备名和 IP 就成了「你的账号在「Chrome」上登录（1.2.3.4）
            // （设备：Chrome · IP：1.2.3.4 · 时间：...）」这种重复句
            String reason = "你的账号在另一台设备上登录，本设备已下线";
            Map<String, Object> detail = new LinkedHashMap<>(4);
            detail.put("deviceName", newDeviceName);
            detail.put("ip", ip);
            detail.put("time", time);
            killSlot(userId, victim, reason, detail, LoginLog.EVENT_REPLACED, "被本台新登录顶下线");
        }
    }

    /**
     * 把一个设备位上的全部会话断掉：先推 kickout 报文，再逐个注销 token，最后落一条流水并回填下线时间。
     *
     * @return 注销掉的会话（token）数
     */
    private int killSlot(Long userId, String slot, String reason, Map<String, Object> detail,
                         int event, String logDetail) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi != null) {
            try {
                // ★ 顺序不能反：会话先注销，socket 还开着，客户端就只知道「连接断了」而不知道为什么
                pushSpi.kickOut(userId, slot, reason, detail);
            } catch (Exception e) {
                log.warn("[设备顶号] 推送 kickout 失败（继续注销会话）: userId={}, slot={}, err={}",
                        userId, slot, e.getMessage());
            }
        }
        int count = 0;
        String type = DeviceIds.typeOf(slot);
        String instance = DeviceIds.instanceOf(slot);
        for (SaTerminalInfo terminal : safeTerminals(userId, type)) {
            if (slot.equals(DeviceIds.slot(terminal.getDeviceType(), terminal.getDeviceId()))) {
                endSession(terminal.getTokenValue(), true);
                count++;
            }
        }
        // 在线键里这个位可能因监听器解析不到设备位而被整键清掉，也可能压根没清；显式下一次为准
        onlineStatusSpi.setOffline(userId, slot);
        // 先把登记行捞出来：它同时是「下线时间回填」与「流水里那台设备叫什么」的唯一可靠来源
        UserDevice row = findDevice(userId, type, instance);
        if (row != null) {
            userDeviceMapper.update(null, Wrappers.<UserDevice>lambdaUpdate()
                    .eq(UserDevice::getId, row.getId())
                    .set(UserDevice::getLastLogoutTime, LocalDateTime.now()));
        }
        String victim = row == null ? DeviceType.of(type).getDesc()
                : resolveName(row.getDeviceName(), row.getUserAgent(), type);
        writeLog(userId, event, type, instance, victim, row == null ? null : row.getIp(), null, logDetail);
        return count;
    }

    /**
     * 读终端列表并挡住两种「查不到」：账号压根没有会话（返回 null）、Sa-Token 内部异常。
     *
     * @param deviceType 为空表示不限类型（跨端下线时用）
     */
    private List<SaTerminalInfo> safeTerminals(Long userId, String deviceType) {
        try {
            List<SaTerminalInfo> list = deviceType == null
                    ? StpUtil.getTerminalListByLoginId(userId)
                    : StpUtil.getTerminalListByLoginId(userId, deviceType);
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            return list.stream()
                    .filter(t -> t != null && !TextUtil.isBlank(t.getTokenValue()))
                    .toList();
        } catch (Exception e) {
            log.debug("[设备顶号] 读取终端列表失败: userId={}, {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 断掉一个会话。
     *
     * @param kicked {@code true} 走 {@code kickout}（下次请求报「账号已在其他设备登录」），
     *               {@code false} 走 {@code logout}（只报「登录已失效」）
     */
    private void endSession(String tokenValue, boolean kicked) {
        if (TextUtil.isBlank(tokenValue)) {
            return;
        }
        try {
            if (kicked) {
                StpUtil.kickoutByTokenValue(tokenValue);
            } else {
                StpUtil.logoutByTokenValue(tokenValue);
            }
        } catch (Exception e) {
            log.debug("[设备顶号] 注销会话失败: {}", e.getMessage());
        }
    }

    /**
     * 按唯一键取一台设备的登记行；{@code instance} 为空代指老客户端，库里存的是空串而不是 null
     * （ MySQL 唯一索引里 null 与 null 不算重复，所以 {@code device_id} 必须 NOT NULL）。
     */
    private UserDevice findDevice(Long userId, String type, String instance) {
        return userDeviceMapper.selectOne(Wrappers.<UserDevice>lambdaQuery()
                .eq(UserDevice::getUserId, userId)
                .eq(UserDevice::getDeviceType, type)
                .eq(UserDevice::getDeviceId, instance == null ? UserDevice.NO_INSTANCE : instance));
    }

    /* ==================== 落库 ==================== */

    /**
     * 一台机器一行：命中唯一键就更新，否则插入。
     *
     * <p>并发同一实例同时登录时两条 {@code insert} 会撞 {@code uk_user_device}，
     * 这里捕住 {@link DuplicateKeyException} 再走一次更新——不捕的话整个设备登记会在监听器里抛出去，
     * 表现为「偶发少一条设备记录」。
     */
    private void upsertDevice(Long userId, String type, String instance, String shown,
                              String ip, String userAgent, LocalDateTime now) {
        String instanceValue = instance == null ? UserDevice.NO_INSTANCE : instance;
        UserDevice exist = findDevice(userId, type, instance);
        if (exist != null) {
            updateDevice(exist.getId(), shown, ip, userAgent, now);
            return;
        }
        UserDevice device = new UserDevice();
        device.setUserId(userId);
        device.setDeviceType(type);
        device.setDeviceId(instanceValue);
        device.setDeviceName(shown);
        device.setIp(ip);
        device.setUserAgent(userAgent);
        device.setLoginCount(1);
        device.setLastLoginTime(now);
        device.setStatus(UserDevice.STATUS_ACTIVE);
        try {
            userDeviceMapper.insert(device);
        } catch (DuplicateKeyException e) {
            UserDevice again = findDevice(userId, type, instance);
            if (again != null) {
                updateDevice(again.getId(), shown, ip, userAgent, now);
            }
        }
    }

    /**
     * {@code login_count} 用 {@code setSql} 自增而不是「读出来加一再写回」：两台并发时后者会丢一次计数。
     */
    private void updateDevice(Long id, String shown, String ip, String userAgent, LocalDateTime now) {
        userDeviceMapper.update(null, Wrappers.<UserDevice>lambdaUpdate()
                .eq(UserDevice::getId, id)
                .setSql("login_count = login_count + 1")
                .set(UserDevice::getDeviceName, shown)
                .set(UserDevice::getIp, ip)
                .set(UserDevice::getUserAgent, userAgent)
                .set(UserDevice::getLastLoginTime, now)
                .set(UserDevice::getLastLogoutTime, null)
                .set(UserDevice::getStatus, UserDevice.STATUS_ACTIVE));
    }

    private void writeLog(Long userId, int event, String type, String instance, String shown,
                          String ip, String userAgent, String detail) {
        try {
            LoginLog entry = new LoginLog();
            entry.setUserId(userId);
            entry.setEvent(event);
            entry.setDeviceType(type == null ? "" : type);
            entry.setDeviceId(instance == null ? UserDevice.NO_INSTANCE : instance);
            entry.setDeviceName(shown);
            entry.setIp(ip);
            entry.setUserAgent(userAgent);
            entry.setDetail(clip(detail));
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("[设备留痕] 流水写入失败: userId={}, event={}, err={}", userId, event, e.getMessage());
        }
    }

    /* ==================== 文案 ==================== */

    /**
     * 设备展示名三级兜底：客户端自报友好名 → UA 推出来的「Chrome on Windows」→ 设备类型中文名。
     *
     * <p>之所以总得有一个值：这个名字会出现在被踢端的详情与「登录设备」列表里，用户要靠它认出那是哪台
     * 机器，宁可退化成类型中文名也不能空着。
     */
    private String resolveName(String deviceName, String userAgent, String type) {
        String friendly = DeviceIds.sanitizeName(deviceName);
        if (friendly != null) {
            return friendly;
        }
        String hint = uaHint(userAgent);
        return hint != null ? hint : DeviceType.of(type).getDesc();
    }

    /**
     * 从 UA 里凑一个能读的设备名，凑不出来返回 {@code null}。
     *
     * <p>只做最保守的字符串识别：本仓没有接任何 UA 解析库，为一个展示字段引入依赖不划算；
     * 而且这个值只用于「让用户认出那是哪台机器」，不参与任何判定，识别错了不影响功能。
     */
    private static String uaHint(String ua) {
        if (TextUtil.isBlank(ua)) {
            return null;
        }
        String browser = null;
        for (String[] pair : UA_BROWSER) {
            if (ua.contains(pair[0])) {
                browser = pair[1];
                break;
            }
        }
        String os = null;
        for (String[] pair : UA_OS) {
            if (ua.contains(pair[0])) {
                os = pair[1];
                break;
            }
        }
        if (browser == null && os == null) {
            return null;
        }
        if (browser == null) {
            return os;
        }
        return os == null ? browser : browser + " on " + os;
    }

    private static String eventDesc(Integer event) {
        if (event == null) {
            return "其他";
        }
        return switch (event) {
            case LoginLog.EVENT_LOGIN -> "登录成功";
            case LoginLog.EVENT_LOGIN_FAIL -> "登录失败";
            case LoginLog.EVENT_LOGOUT -> "退出登录";
            case LoginLog.EVENT_REPLACED -> "被同端新登录顶下线";
            case LoginLog.EVENT_KICKED -> "被平台停用";
            case LoginLog.EVENT_REVOKED -> "设备被撤销";
            case LoginLog.EVENT_CREDENTIAL_CHANGED -> "凭证变更导致全端下线";
            default -> "其他";
        };
    }

    /** detail 列宽 255，超长直接截断，不能让一句文案把整条留痕写失败 */
    private String clip(String detail) {
        if (TextUtil.isBlank(detail)) {
            return null;
        }
        String value = detail.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }
}
