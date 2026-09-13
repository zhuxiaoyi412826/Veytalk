package com.im.group.service;

import com.im.common.api.PageResult;
import com.im.common.domain.PageQuery;
import com.im.group.dto.req.CreateGroupRequest;
import com.im.group.dto.req.UpdateGroupRequest;
import com.im.group.dto.vo.GroupMemberVO;
import com.im.group.dto.vo.GroupVO;

import java.util.Collection;
import java.util.List;

/**
 * 群组业务接口。
 *
 * <p>所有方法的第一个参数都是操作者 ID，由 Controller 从 {@code SecurityUtil} 取出后显式传入，
 * Service 内部不再读上下文：这样 SPI 实现与定时任务也能复用同一套逻辑，
 * 而且权限校验点在读代码时一眼可见，不会藏在某个静态工具调用里。
 */
public interface GroupService {

    /**
     * 创建群组：写群、写群主成员行、写初始成员、创建群会话并发一条建群通知。
     *
     * @return 群详情，含刚创建好的群会话 ID
     */
    GroupVO create(Long operatorId, CreateGroupRequest request);

    /**
     * 修改群名称 / 头像 / 公告，群主与管理员可改。
     *
     * <p>入参字段为 {@code null} 表示不改，空串表示清空（群名不允许清空）。
     */
    GroupVO update(Long operatorId, Long groupId, UpdateGroupRequest request);

    /**
     * 群详情，非成员也能查看基本信息，但 {@code myRole} 等视角字段为空。
     */
    GroupVO detail(Long viewerId, Long groupId);

    /**
     * 我加入的群列表，按入群时间倒序，含成员数与我的角色。
     */
    List<GroupVO> listMine(Long userId);

    /**
     * 邀请成员入群，群主与管理员可操作。
     *
     * <p>已在群内的用户会被静默跳过而不是报错：邀请列表常常是从好友列表整批勾选的，
     * 里面混进一个已入群的人不应该让整批操作失败。
     *
     * @return 实际新增成功的用户 ID 列表
     */
    List<Long> addMembers(Long operatorId, Long groupId, Collection<Long> userIds);

    /**
     * 移除成员，群主可移除除自己以外的任何人，管理员只能移除普通成员。
     */
    void removeMember(Long operatorId, Long groupId, Long userId);

    /**
     * 主动退群。群主必须先转让群主，否则群里会留下一个没有群主的僵尸群。
     */
    void quit(Long operatorId, Long groupId);

    /**
     * 设置成员角色（2 管理员 / 3 普通成员），仅群主可操作。
     */
    void updateRole(Long operatorId, Long groupId, Long userId, Integer role);

    /**
     * 转让群主，仅群主可操作。原群主降级为普通成员并留在群内。
     */
    void transfer(Long operatorId, Long groupId, Long newOwnerId);

    /**
     * 单人禁言或解除禁言，群主与管理员可操作。
     *
     * @param muted   true 禁言 false 解除
     * @param minutes 禁言时长（分钟），为空表示无限期；解除时忽略
     */
    void muteMember(Long operatorId, Long groupId, Long userId, boolean muted, Integer minutes);

    /**
     * 开关全员禁言，群主与管理员可操作。群主与管理员自身不受全员禁言影响。
     */
    void muteAll(Long operatorId, Long groupId, boolean muteAll);

    /**
     * 解散群，仅群主可操作。群会话保留，最后一条消息是解散通知。
     */
    void dismiss(Long operatorId, Long groupId);

    /**
     * 修改我在群内的昵称，留空表示清除并回退展示账号昵称。
     */
    void updateMyNickname(Long operatorId, Long groupId, String nicknameInGroup);

    /**
     * 分页查询群成员，仅群成员可查看。群主与管理员排在前面。
     */
    PageResult<GroupMemberVO> pageMembers(Long viewerId, Long groupId, PageQuery query);
}
