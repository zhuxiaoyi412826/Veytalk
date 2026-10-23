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
 * <p>写库只有一条 {@code UPDATE im_user SET status}，它自己就是原子的；
 * 后面的踢连接与注销会话是<b>不该回滚也不该被回滚</b>的副作用（把会话清掉失败了，
 * 最坏是他还能用旧 token 撑到过期，回滚数据库反而会让「已发出的封禁」失去依据）。
 * 因此这里刻意不加 {@code @Transactional}，把两步串成「先落库、后尽力断线」。
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
    public AdminUserDTO setDisabled(Long userId, boolean disabled, Long operatorId, String reason) {
        BusinessException.throwIf(userId == null, ResultCode.BAD_REQUEST, "缺少用户 ID");
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
        if (disabled != alreadyDisabled) {
            User patch = new User();
            patch.setId(userId);
            patch.setStatus(disabled ? User.STATUS_DISABLED : User.STATUS_NORMAL);
            userMapper.updateById(patch);
            log.info("[管理后台] 账号状态变更: userId={}, account={}, disabled={}, operator={}, reason={}",
                    userId, user.getUsername(), disabled, operatorId, reason);
        } else if (!disabled) {
            // 本来就是正常账号，无需重复恢复
            return detail(userId);
        }
        if (disabled) {
            // 状态已符合也要再断一次线：上一次禁用可能正好失败在这一步之后，账上已经是禁用而会话还活着
            evictSessions(userId);
        }
        return detail(userId);
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
    private void evictSessions(Long userId) {
        PushSpi pushSpi = pushSpiProvider.getIfAvailable();
        if (pushSpi != null) {
            try {
                pushSpi.kickOut(userId, null, "账号已被平台停用");
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
