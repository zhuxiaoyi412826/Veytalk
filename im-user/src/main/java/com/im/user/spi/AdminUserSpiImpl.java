package com.im.user.spi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminUserDTO;
import com.im.common.domain.AdminUserQueryDTO;
import com.im.common.domain.AdminRoleDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminUserSpi;
import com.im.common.spi.OnlineStatusSpi;
import com.im.common.spi.PermissionProvider;
import com.im.common.spi.PushSpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.User;
import com.im.user.entity.Role;
import com.im.user.entity.UserRole;
import com.im.user.entity.UserWallet;
import com.im.user.mapper.RoleMapper;
import com.im.user.mapper.UserMapper;
import com.im.user.mapper.UserRoleMapper;
import com.im.user.mapper.UserWalletMapper;
import com.im.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code AdminUserSpi} 的实现：后台要的用户档案与「禁用/恢复」动作。
 *
 * <h2>为什么用户表只有本模块能读</h2>
 *
 * <p>{@code im_user} 的映射只出现在 im-user（全仓 {@code @TableName} 一表一次），
 * 所以 im-admin 想看用户列表、想封号，只能经这个契约把活交给我们。
 * 好处是「什么字段能出用户表」这件事收在一个文件里判：
 * 密码列在实体上就带 {@code @JsonIgnore}，手机号在这里过一遍 {@code maskPhone} 才给出去，
 * 后台因此拿不到全号，也就少一条能把整张用户表泄露出去的路径。
 *
 * <h2>批量方法是必需的，不是锦上添花</h2>
 *
 * <p>后台一屏二十行，若只能逐个 {@code detail}，就是二十次查询乘上「档案 = 用户行 + 钱包 + 角色 + 在线态」
 * 四份数据。这里把后三者各压成一次批量查询（钱包按主键批取、管理员按 role_id 批取、
 * 在线态按 {@code filterOnline} 一次算完），代价是 {@code listByIds} 的入参规模必须由调用方夹住
 * ——契约里写了后台一屏封顶 100 行，本类再夹一次上限，两道闸都不信对方。
 *
 * <h2>禁用为什么不带事务</h2>
 *
 * <p>写库只有一条 {@code UPDATE im_user SET status, disable_until}，它自己就是原子的；
 * 后面的踢连接与注销会话是<b>不该回滚也不该被回滚</b>的副作用（把会话清掉失败了，
 * 最坏是他还能用旧 token 撑到过期，回滚数据库反而会让「已发出的封禁」失去依据）。
 * 因此这里刻意不加 {@code @Transactional}，把两步串成「先落库、后尽力断线」。
 *
 * <h2>限时封禁的三个口径</h2>
 *
 * <ul>
 *   <li><b>到期时间由服务端算</b>：契约上传的是「多少分钟」而不是一个绝对时间戳，
 *       管理员浏览器上的时钟不可信，一个偏快的到期时间就是提前解封；</li>
 *   <li><b>{@code disable_until IS NULL} 就是永久</b>，不用一个极大值代指永久：
 *       9999-12-31 会被任何「按到期时间排序」的列表当成一条真的待办，也会被自动解封任务扫到；</li>
 *   <li><b>自动解封不通知本人</b>：那一刻他必然处于离线状态（封禁时已经踢过、
 *       又登不进来），推送无处可发；他下次登录自然成功，不需要一条通知提前告知。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserSpiImpl implements AdminUserSpi {

    /** 关键词长度上限：再长的串只可能是粘贴进来的，不可能是「找一个人」 */
    private static final int KEYWORD_MAX = 32;
    /** 每页上限，与后台队列同口径（一屏要按行拼钱包与角色，放开只会把接口拖慢） */
    private static final long PAGE_SIZE_MAX = 100;
    /** 批量取档案的 ID 数上限，超过截断：契约里的「调用方应控住规模」在这里兑现成硬约束 */
    private static final int BATCH_MAX = 200;
    /** 封禁时长上限（分钟，= 365 天）：比这更久就该用永久封禁，而不是给一个「临时」封禁挂个十年后的到期日 */
    private static final long BAN_MINUTES_MAX = 365L * 60 * 24;
    /** 一轮自动解封最多处理多少行：到期时间集中命中时留在下个周期接着做，不在一个循环里长占连接 */
    private static final int UNBAN_BATCH_MAX = 200;
    /** 踢下线报文里的到期时间格式：给人读的，到分钟就够（精确到秒只会让人怀疑是不是自己看错了） */
    private static final DateTimeFormatter KICK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final UserMapper userMapper;
    private final UserWalletMapper userWalletMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final OnlineStatusSpi onlineStatusSpi;
    private final AuthService authService;
    /** 改角色后必须失效这个用户的权限缓存（缓存 30 分钟，不等它对方新权限最长半小时不生效） */
    private final PermissionProvider permissionProvider;
    /** 禁用要顺带踢掉长连接；im-websocket 未装配时禁用本身仍然生效（下次登录自然进不去） */
    private final ObjectProvider<PushSpi> pushSpiProvider;

    @Override
    public PageResult<AdminUserDTO> pageUsers(AdminUserQueryDTO query) {
        long current = Math.max(1L, query.getCurrent());
        long size = clipSize(query.getSize());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        String keyword = clipKeyword(query.getKeyword());
        if (keyword != null) {
            // 账号/昵称用包含匹配（人记不全名字），手机号只用前缀匹配：
            // phone 上是唯一索引，like '138%' 还能走索引，'%138%' 则是全表扫
            wrapper.and(w -> w.like(User::getUsername, keyword)
                    .or().like(User::getNickname, keyword)
                    .or().likeRight(User::getPhone, keyword));
        }
        if (query.getStatus() != null) {
            BusinessException.throwIf(query.getStatus() != User.STATUS_NORMAL && query.getStatus() != User.STATUS_DISABLED,
                    ResultCode.BAD_REQUEST, "未知的账号状态");
            wrapper.eq(User::getStatus, query.getStatus());
        }
        // 排序口径写死：注册时间倒序 + ID 兜底（同秒注册时保持稳定），不接受外部传 order by
        wrapper.orderByDesc(User::getCreateTime).orderByDesc(User::getId);
        Page<User> page = userMapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(enrich(page.getRecords()), page.getTotal(), current, size);
    }

    @Override
    public AdminUserDTO detail(Long userId) {
        if (userId == null) {
            return null;
        }
        List<AdminUserDTO> rows = enrich(List.of(userMapper.selectById(userId)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<AdminUserDTO> listByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(BATCH_MAX)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        // 查不到的 ID 不出现在结果里：后台把「缺项」当作「账号已注销」，比补一个空对象更省事
        return enrich(userMapper.selectByIds(ids));
    }

    @Override
    public AdminUserDTO setDisabled(Long userId, boolean disabled, Long operatorId, String reason, Long disableMinutes) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        // 时长在本类再夹一次：调用方有两个（后台用户页与举报处置），只在前端夹等于没夹
        if (disabled && disableMinutes != null) {
            BusinessException.throwIf(disableMinutes <= 0, ResultCode.BAD_REQUEST,
                    "封禁时长必须是正数分钟，永久封禁请不传该参数");
            BusinessException.throwUnless(disableMinutes <= BAN_MINUTES_MAX, ResultCode.BAD_REQUEST,
                    "封禁时长最长 " + (BAN_MINUTES_MAX / 60 / 24) + " 天，更久请改用永久封禁");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        boolean alreadyDisabled = user.getStatus() != null && user.getStatus() == User.STATUS_DISABLED;
        if (disabled) {
            // 封掉自己等于把后台门锁上并从里面拆掉钥匙：库里没有任何账号还带着 system:manage
            BusinessException.throwIf(userId.equals(operatorId), ResultCode.ADMIN_SELF_FORBIDDEN);
            BusinessException.throwUnless(mayDisableAdmin(userId), ResultCode.BUSINESS_ERROR,
                    "这是最后一个可用的管理员账号，禁用后后台将无人可登录");
        }
        LocalDateTime until = disabled && disableMinutes != null
                ? LocalDateTime.now().plusMinutes(disableMinutes)
                : null;
        if (disabled) {
            // 已经禁用也照写：这一支同时承担「改期」——对封着的人重新下发一个时长就是把到期时间换掉。
            // 若只在状态翻转时写库，管理员想把 1 天延长到 7 天就只能先恢复再禁用，中间那一秒是真把门打开了
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, userId)
                    .set(User::getStatus, User.STATUS_DISABLED)
                    .set(User::getDisableUntil, until));
            log.info("[管理后台] 账号已禁用: userId={}, account={}, until={}, operator={}, reason={}",
                    userId, user.getUsername(), until == null ? "永久" : until, operatorId, reason);
        } else if (alreadyDisabled) {
            userMapper.update(null, Wrappers.<User>lambdaUpdate()
                    .eq(User::getId, userId)
                    .set(User::getStatus, User.STATUS_NORMAL)
                    // 提前恢复必须把到期时间一起清掉：留着它，后台会在这个已正常的账号上继续显示一个作废的日期
                    .set(User::getDisableUntil, null));
            log.info("[管理后台] 账号已恢复: userId={}, account={}, 原到期时间={}, operator={}",
                    userId, user.getUsername(),
                    user.getDisableUntil() == null ? "永久" : user.getDisableUntil(), operatorId);
        }
        if (disabled) {
            // 状态已符合也要再断一次线：上一次禁用可能正好失败在这一步之后，账上已经是禁用而会话还活着
            evictSessions(userId, kickText(until, reason));
        }
        // disabled=false 且本来就正常时不进上面任何一支：无变更，直接回档案
        return detail(userId);
    }

    /**
     * 到期自动解封：把 {@code status=0} 且 {@code disable_until <= now} 的账号放回正常态。
     *
     * <p>由 {@code UserBanExpireTask} 周期调用。它<b>不在 {@code AdminUserSpi} 契约上</b>：
     * 把一个「批量改账号状态」的方法开到契约上，等于给每个依赖该契约的模块都开了那扇门。
     *
     * <h3>为什么条件里必须有 {@code isNotNull}</h3>
     *
     * <p>永久封禁与已注销的账号在库里长得一模一样（{@code status=0} 且
     * {@code disable_until IS NULL}，注销行还额外带 {@code deleted=1}）。少这一条条件，
     * 任务就会把客服亲手永久封的人一并放出去；对注销账号更糟：注销链路已经把昵称、
     * 密码、手机号全抹掉了，把它解回 {@code status=1} 得到的是一个不可识别却能登录的空壳。
     * {@code @TableLogic} 本来就会滤掉注销行，但那属于实现的附带效果，不能当依据；
     * 所以这里既写了 {@code isNotNull}，也依赖逻辑删除，两道都要在。
     *
     * <h3>多实例部署</h3>
     *
     * <p>每个节点都会各自扫一遍，不做分布式锁：下面那条 UPDATE 的 WHERE 里带着与扫描完全相同的
     * 三个条件，后到的节点只会更新 0 行，天然幂等。真上多实例时该担心的是扫描开销，
     * {@code idx_disable_until} 就是为这条语句加的——绝大多数用户这一列是 NULL，
     * 走索引的代价与「被封过的人数」成正比，而不是与用户总数成正比。
     *
     * @return 本次实际解封的账号数
     */
    public int autoUnbanExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<User> expired = userMapper.selectList(Wrappers.<User>lambdaQuery()
                .eq(User::getStatus, User.STATUS_DISABLED)
                .isNotNull(User::getDisableUntil)
                .le(User::getDisableUntil, now)
                .last("LIMIT " + UNBAN_BATCH_MAX));
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> ids = expired.stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        // WHERE 里重复一遍扫描条件：从查出到写回之间管理员可能已经手工恢复或改了到期时间，那时这一行不该被动
        int changed = userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .in(User::getId, ids)
                .eq(User::getStatus, User.STATUS_DISABLED)
                .isNotNull(User::getDisableUntil)
                .le(User::getDisableUntil, now)
                .set(User::getStatus, User.STATUS_NORMAL)
                .set(User::getDisableUntil, null));
        if (changed > 0) {
            log.info("[自动解封] 封禁到期已恢复: count={}, userIds={}", changed, ids.stream().limit(20).toList());
        }
        return changed;
    }

    /**
     * 踢下线报文里的那句话。
     *
     * <p>{@code kickout} 的 reason 是唯一能在这个时刻送达用户的信息（他马上就没有登录态了），
     * 所以限时封禁要把到期时间写进去：「你被禁了」与「你被禁到 11 月 2 日」是两种体验，
     * 后者不需要用户去猜该不该来问客服。事由是管理员写给审计看的，这里一并带上；
     * 为空就只说状态，不硬凑一句「违反社区规定」这种没依据的话。
     */
    private String kickText(LocalDateTime until, String reason) {
        StringBuilder text = new StringBuilder("账号已被平台停用");
        text.append(until == null ? "（永久）" : "，将于 " + KICK_TIME.format(until) + " 自动解封");
        if (!TextUtil.isBlank(reason)) {
            text.append("；事由：").append(reason);
        }
        return text.toString();
    }

    @Override
    public List<AdminRoleDTO> listRoles() {
        // 只给启用且未删除的角色：BaseEntity 上的 @TableLogic 已自动过滤 deleted，这里只再筛 status=1
        return roleMapper.selectList(Wrappers.<Role>lambdaQuery().eq(Role::getStatus, 1))
                .stream()
                .map(role -> AdminRoleDTO.builder()
                        .id(role.getId())
                        .roleCode(role.getRoleCode())
                        .roleName(role.getRoleName())
                        .description(role.getDescription())
                        .build())
                .toList();
    }

    @Override
    public List<Long> listUserRoleIds(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<Long> setUserRoles(Long userId, List<Long> roleIds, Long operatorId) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
        // 自我豁免：超管把自己从 admin 摘下来就是把后台门锁上并从里面拆钥匙，与禁用自己同一理由
        BusinessException.throwIf(userId.equals(operatorId), ResultCode.ADMIN_SELF_FORBIDDEN);
        User user = userMapper.selectById(userId);
        BusinessException.throwIf(user == null, ResultCode.USER_NOT_FOUND);

        // 入参归一：去 null、去重；未知角色主键（不在可分配列表里）直接拒，不做静默部分生效
        List<Long> wanted = roleIds == null ? List.of() : roleIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        Set<Long> assignable = listRoles().stream().map(AdminRoleDTO::getId).collect(Collectors.toSet());
        for (Long roleId : wanted) {
            BusinessException.throwIf(!assignable.contains(roleId), ResultCode.BAD_REQUEST, "包含未知或已停用的角色");
        }

        Long adminRoleId = roleMapper.selectIdByRoleCode(ImConstants.ROLE_ADMIN);
        boolean hadAdmin = adminRoleId != null && listUserRoleIds(userId).contains(adminRoleId);
        boolean keepsAdmin = adminRoleId != null && wanted.contains(adminRoleId);
        // 这次改动会把他从 admin 摘掉时，先确认「摘完还剩至少一个可用管理员」，否则后台因这次改动变成无人可登
        if (hadAdmin && !keepsAdmin) {
            BusinessException.throwUnless(mayDisableAdmin(userId), ResultCode.BUSINESS_ERROR,
                    "这是最后一个可用的管理员账号，撤销其 admin 角色后后台将无人可登录");
        }

        // 整体替换：先物理删光旧绑定（关联表无软删列，删了才能立刻重加同一角色），再逐条插新的
        userRoleMapper.delete(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
        for (Long roleId : wanted) {
            userRoleMapper.insert(new UserRole(userId, roleId));
        }
        // 权限缓存不失效，对方最长 30 分钟后才看到新权限，表现为「明明配了却没生效」
        permissionProvider.evictCache(userId);
        log.info("[管理后台] 用户角色变更: userId={}, account={}, roles={}, operator={}",
                userId, user.getUsername(), wanted, operatorId);
        return listUserRoleIds(userId);
    }

    /**
     * 断掉这个用户的一切凭据：先推 kickout 报文，再注销登录态。
     *
     * <p>顺序不能反，与 {@code AuthServiceImpl.kickSameDevice} 同一个理由：
     * 会话先没了，socket 还开着，客户端只会看到「连接断了」，
     * 而 kickout 报文是唯一能告诉他「你是被平台停用的」的信息。
     *
     * <p>{@code logoutEverywhere} 内部已包含清除在线键与向好友广播下线，这里不重复做。
     */
    private void evictSessions(Long userId, String kickReason) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi != null) {
            try {
                pushSpi.kickOut(userId, null, kickReason);
            } catch (Exception e) {
                log.warn("[管理后台] 停用账号时踢出连接失败: userId={}, err={}", userId, e.getMessage());
            }
        }
        try {
            authService.logoutEverywhere(userId);
        } catch (Exception e) {
            log.warn("[管理后台] 停用账号时注销登录态失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 能不能禁用这个账号：只有目标是管理员时才需要查「还剩几个可用管理员」。
     *
     * <p>判据是「持有 admin 角色<b>且状态正常</b>的账号数 &gt; 1」：只数角色会把一个已被禁用的
     * 管理员也算成退路，结果是禁掉最后一个可用管理员之后才发现后台进不去。
     */
    private boolean mayDisableAdmin(Long userId) {
        if (!hasAdminRole(userId)) {
            return true;
        }
        Long adminRoleId = roleMapper.selectIdByRoleCode(ImConstants.ROLE_ADMIN);
        if (adminRoleId == null) {
            // 角色被删了却还有人持有绑定：视为没有可用的退路，宁可不许禁
            return false;
        }
        List<Long> adminIds = userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getRoleId, adminRoleId))
                .stream()
                .map(UserRole::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (adminIds.isEmpty()) {
            return false;
        }
        Long usable = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .in(User::getId, adminIds)
                .eq(User::getStatus, User.STATUS_NORMAL));
        return usable != null && usable > 1L;
    }

    private boolean hasAdminRole(Long userId) {
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        return roles != null && roles.contains(ImConstants.ROLE_ADMIN);
    }

    /**
     * 实体 → 后台档案，批量补齐钱包余额、管理员标记与在线状态。
     *
     * <p>传入的是已经查好的一批用户行，本方法只再加三次查询（钱包、角色绑定、在线集合），
     * 与行数无关——这是 {@code listByIds} 存在的意义。
     */
    private List<AdminUserDTO> enrich(List<User> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(User::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> balances = balanceOf(ids);
        Set<Long> adminIds = adminIdsOf(ids);
        Set<Long> onlineIds = new HashSet<>(onlineStatusSpi.filterOnline(ids));
        List<AdminUserDTO> rows = new ArrayList<>(users.size());
        for (User user : users) {
            rows.add(AdminUserDTO.builder()
                    .userId(user.getId())
                    .account(user.getUsername())
                    .nickname(user.getNickname())
                    .avatar(user.getAvatar())
                    // 手机号是整张表里最值钱的列，出用户表只走脱敏形态
                    .phone(TextUtil.maskPhone(user.getPhone()))
                    .status(user.getStatus())
                    .disableUntil(user.getDisableUntil())
                    .online(onlineIds.contains(user.getId()))
                    .balance(balances.get(user.getId()))
                    .createTime(user.getCreateTime())
                    .admin(adminIds.contains(user.getId()))
                    .build());
        }
        return rows;
    }

    /** 钱包按主键批取；没建过钱包行的用户不在结果里（余额留空而不是 0，前端显示「—」） */
    private Map<Long, Long> balanceOf(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userWalletMapper.selectList(Wrappers.<UserWallet>lambdaQuery()
                        .in(UserWallet::getUserId, ids))
                .stream()
                .filter(wallet -> wallet.getUserId() != null)
                .collect(Collectors.toMap(UserWallet::getUserId, wallet -> wallet.getBalance() == null ? 0L : wallet.getBalance(),
                        (left, right) -> left));
    }

    /**
     * 这批人里哪些持有 admin 角色：先取角色主键再一次 {@code IN}，不在循环里查。
     */
    private Set<Long> adminIdsOf(List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        Long adminRoleId = roleMapper.selectIdByRoleCode(ImConstants.ROLE_ADMIN);
        if (adminRoleId == null) {
            return Collections.emptySet();
        }
        return userRoleMapper.selectList(Wrappers.<UserRole>lambdaQuery()
                        .eq(UserRole::getRoleId, adminRoleId)
                        .in(UserRole::getUserId, ids))
                .stream()
                .map(UserRole::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String clipKeyword(String keyword) {
        if (TextUtil.isBlank(keyword)) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.length() > KEYWORD_MAX ? trimmed.substring(0, KEYWORD_MAX) : trimmed;
    }

    private long clipSize(long size) {
        if (size <= 0) {
            return 20L;
        }
        return Math.min(size, PAGE_SIZE_MAX);
    }
}
