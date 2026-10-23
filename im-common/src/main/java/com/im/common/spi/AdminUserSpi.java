package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminRoleDTO;
import com.im.common.domain.AdminUserDTO;
import com.im.common.domain.AdminUserQueryDTO;

import java.util.Collection;
import java.util.List;

/**
 * 后台用户管理契约，由 im-user 实现。
 *
 * <p>星型架构下 im-admin 不能引 {@code UserMapper}，但它确实需要「按关键词翻页找用户」、「批量拿回一批
 * 用户的账号状态」和「禁用/恢复账号」这几件只有用户模块能做的事。这里刻意只开四个口子：
 * <b>后台改不了昵称、改不了密码、也读不到手机号全号</b>——一期没有这个需求，
 * 多开一个方法就多一条能把用户资料改坏的路径，收紧比事后删接口容易。
 *
 * <p>实现方的三条硬约束（写在接口上而不是只写在 im-user 的实现注释里，因为换个人实现也要守）：
 * <ul>
 *   <li>分页大小与关键词长度要自己夹上限，调用方（后台）传来的参数不可信；</li>
 *   <li>排序口径写死为注册时间倒序，不接受外部传入的 order by；</li>
 *   <li>{@code phone} 必须脱敏后再返回。</li>
 * </ul>
 */
public interface AdminUserSpi {

    /**
     * 翻页查用户。
     */
    PageResult<AdminUserDTO> pageUsers(AdminUserQueryDTO query);

    /**
     * 查单个用户的后台档案。
     *
     * @return 不存在时返回 {@code null}
     */
    AdminUserDTO detail(Long userId);

    /**
     * 批量取后台档案，给举报队列与看板上的「被举报人现在什么状态」用。
     *
     * <p>为什么需要一个批量方法而不是让调用方逐行 {@link #detail}：
     * 一屏二十行就是二十次查询，而这个接口存在的理由恰好是「后台不能自己去 join 用户表」，
     * 于是 N+1 只能在这里堵掉。不存在的 ID 直接出现在结果里（缺失项不补一个空对象），
     * 调用方把「查不到」当作「账号已注销」处理。
     *
     * @param userIds 调用方应控住规模（后台一屏最多 100 行），为空直接返回空列表
     */
    List<AdminUserDTO> listByIds(Collection<Long> userIds);

    /**
     * 禁用 / 恢复账号。
     *
     * <p>禁用即把 {@code im_user.status} 置 0（<b>零 DDL：这一列本来就有</b>），
     * 同时踢掉该用户的全部 WebSocket 连接：不踢的话，他手上那条长连接还能继续收发消息，
     * 「禁用」要等到下次登录才生效，那是个会说谎的按钮。
     *
     * <p>实现方应保证幂等（重复禁用返回当前状态即可），并拒绝两种危险调用：
     * 禁用自己、禁用最后一个管理员账号。
     *
     * @param userId     目标用户
     * @param disabled   {@code true} 禁用，{@code false} 恢复
     * @param operatorId 操作管理员，用于审计日志与自我豁免判定
     * @param reason     事由，会写进服务端日志
     * @return 处置后的用户档案
     */
    AdminUserDTO setDisabled(Long userId, boolean disabled, Long operatorId, String reason);

    /* ==================== 后台角色分配（二期） ==================== */

    /**
     * 列出可分配的角色（{@code status=1} 且未删除）。给后台「配角色」的下拉用。
     */
    List<AdminRoleDTO> listRoles();

    /**
     * 查某个用户当前持有的角色主键。
     */
    List<Long> listUserRoleIds(Long userId);

    /**
     * 用传入的角色集合<b>整体替换</b>该用户的角色，改完必须失效该用户的权限缓存。
     *
     * <p>实现方要守住三条：
     * <ul>
     *   <li><b>拒绝改自己的角色</b>（{@code userId.equals(operatorId)}）：超管把自己从 admin 摘下来
     *       等于把后台门锁上并从里面拆钥匙，与 {@link #setDisabled} 的自我豁免同一理由；</li>
     *   <li>若这次改动会让某个<b>可用的 admin 角色持有者</b>不再持有 admin、且他是最后一个可用管理员，
     *       要拒绝（后台不能因这次改动变成无人可登录）；</li>
     *   <li>写库成功后调用权限数据源的 {@code evictCache(userId)}，否则对方最长要 30 分钟后才生效。</li>
     * </ul>
     *
     * @param userId     目标用户
     * @param roleIds    期望的角色主键集合（整体替换语义，空集=清空全部角色）
     * @param operatorId 操作管理员，用于自我豁免判定与日志
     * @return 变更后的角色主键集合
     */
    List<Long> setUserRoles(Long userId, List<Long> roleIds, Long operatorId);
}
