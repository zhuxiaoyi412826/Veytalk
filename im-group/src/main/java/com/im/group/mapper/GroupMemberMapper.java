package com.im.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.enums.GroupRole;
import com.im.group.dto.po.GroupMemberCount;
import com.im.group.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 群成员 Mapper。
 *
 * <p>「在群」一律以 {@code status = 1} 判定，退群与被移出都只改状态不删行，
 * 因此本接口里凡是对外的查询方法都带上了状态条件，避免调用方漏写而把已退群的人当成成员。
 */
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    /**
     * 查询成员行，不限状态。重新入群时需要拿到历史行来复用，避免与唯一键 {@code uk_group_user} 冲突。
     */
    default GroupMember selectRow(Long groupId, Long userId) {
        return selectOne(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 查询在群成员行，已退群或不存在都返回 {@code null}。
     */
    default GroupMember selectActive(Long groupId, Long userId) {
        return selectOne(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .last("LIMIT 1"));
    }

    /**
     * 群内全部在群成员行，按角色与入群时间排序：群主与管理员排在前面，成员列表看起来才像个管理层级。
     */
    default List<GroupMember> selectActiveMembers(Long groupId) {
        return selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .orderByAsc(GroupMember::getRole)
                .orderByAsc(GroupMember::getJoinTime)
                .orderByAsc(GroupMember::getId));
    }

    /**
     * 分页查询在群成员，角色升序保证群主始终在第一页第一条。
     */
    default Page<GroupMember> selectActiveMemberPage(Page<GroupMember> page, Long groupId) {
        return selectPage(page, Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .orderByAsc(GroupMember::getRole)
                .orderByAsc(GroupMember::getJoinTime)
                .orderByAsc(GroupMember::getId));
    }

    /**
     * 群内全部在群成员 ID，供消息模块计算接收者。只取一列，不解析整行。
     */
    default List<Long> selectActiveMemberIds(Long groupId) {
        return selectList(Wrappers.<GroupMember>lambdaQuery()
                        .select(GroupMember::getUserId)
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP))
                .stream()
                .map(GroupMember::getUserId)
                .toList();
    }

    /**
     * 在群成员数，用于入群前判断是否已达上限。
     */
    default long countActive(Long groupId) {
        return selectCount(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP));
    }

    /**
     * 我加入的全部群成员行，按入群时间倒序，用于「我的群聊」列表。
     */
    default List<GroupMember> selectActiveByUser(Long userId) {
        return selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .orderByDesc(GroupMember::getJoinTime)
                .orderByDesc(GroupMember::getId));
    }

    /**
     * 批量查询我在若干群里的成员行，供会话列表一次性回填 myRole / myNickname，避免 N+1。
     */
    default List<GroupMember> selectRowsByUserAndGroups(Long userId, Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getUserId, userId)
                .in(GroupMember::getGroupId, groupIds)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP));
    }

    /**
     * 一次算完多个群的在群成员数，供「我的群聊」列表使用。
     *
     * <p>返回投影而不是实体：自定义 SQL 不走 {@code autoResultMap}，也不应该把整行字段都拉回来。
     * 列别名交给 {@code map-underscore-to-camel-case} 自动映射到 {@link GroupMemberCount}。
     */
    @Select("""
            <script>
                SELECT group_id, COUNT(*) AS member_count
                FROM im_group_member
                WHERE status = 1
                  AND group_id IN
                  <foreach collection="groupIds" item="gid" open="(" separator="," close=")">
                      #{gid}
                  </foreach>
                GROUP BY group_id
            </script>
            """)
    List<GroupMemberCount> selectMemberCounts(@Param("groupIds") Collection<Long> groupIds);

    /**
     * 把已退群的成员行恢复为在群状态，并重置角色、禁言与入群时间。
     *
     * <p>禁言状态必须一并清掉：上次退群前被禁言的记录如果留着，
     * 重新入群后会莫名处在禁言中，而群主根本看不到这条历史。
     *
     * @return 影响行数，0 表示行不存在（并发下已被物理清理），调用方需转为插入
     */
    default int restore(Long groupId, Long userId, Integer role, LocalDateTime joinTime) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .set(GroupMember::getRole, role)
                .set(GroupMember::getMuted, 0)
                // 到期时间要写成真正的 SQL NULL：用 set(field, null) 会生成一个 jdbcType 未知的空参数，
                // 部分驱动会因无法推断类型而报错，直接拼 SQL 片段没有这个风险
                .setSql("mute_end_time = NULL")
                .set(GroupMember::getJoinTime, joinTime)
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));
    }

    /**
     * 单人禁言。
     *
     * @param endTime 禁言到期时间，为空表示无限期
     */
    default int mute(Long groupId, Long userId, LocalDateTime endTime) {
        var wrapper = Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getMuted, 1)
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP);
        if (endTime == null) {
            wrapper.setSql("mute_end_time = NULL");
        } else {
            wrapper.set(GroupMember::getMuteEndTime, endTime);
        }
        return update(null, wrapper);
    }

    /**
     * 解除单人禁言。
     */
    default int unmute(Long groupId, Long userId) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getMuted, 0)
                .setSql("mute_end_time = NULL")
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId));
    }

    /**
     * 调整群内角色，仅群主可调用（权限在 Service 层校验）。
     *
     * <p>条件里限定当前行必须是普通成员或管理员，避免并发下把群主那一行改成管理员，
     * 造成一个群同时没有群主又有两个群主的脏数据。
     */
    default int updateRole(Long groupId, Long userId, Integer role) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getRole, role)
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .ne(GroupMember::getRole, GroupRole.OWNER.getCode()));
    }

    /**
     * 转让群主的第一步：把接任者置为群主。
     *
     * <p>不能复用 {@link #updateRole}，两者的语义正好相反：{@code updateRole} 的条件里排除了
     * 当前角色为群主的行（防止误改群主），而这里恰恰要把一个非群主的行改成群主。
     *
     * @return 影响行数，0 表示接任者已不在群内
     */
    default int promoteOwner(Long groupId, Long userId) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getRole, GroupRole.OWNER.getCode())
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .ne(GroupMember::getRole, GroupRole.OWNER.getCode()));
    }

    /**
     * 转让群主的第二步：把原群主降为普通成员。
     *
     * <p>条件里限定当前必须是群主，避免并发下把别人的角色一起改掉。
     * 转让的两步更新在同一个事务里，任一步返回 0 都整体回滚，
     * 不会出现「新群主已就位、老群主还是群主」的双群主脏数据。
     *
     * @return 影响行数
     */
    default int demoteOwner(Long groupId, Long userId) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getRole, GroupRole.MEMBER.getCode())
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP)
                .eq(GroupMember::getRole, GroupRole.OWNER.getCode()));
    }

    /**
     * 变更在群状态，退群与被移出共用。
     *
     * <p>条件里带 {@code status = 1}，重复退群只会有一次生效，调用方据返回值判断幂等。
     */
    default int updateStatus(Long groupId, Long userId, int status) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getStatus, status)
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP));
    }

    /**
     * 群解散后把全部成员置为已退群，保留成员行以便追溯谁曾经在这个群里。
     */
    default int dismissAllMembers(Long groupId) {
        return update(null, Wrappers.<GroupMember>lambdaUpdate()
                .set(GroupMember::getStatus, GroupMember.STATUS_LEFT)
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, GroupMember.STATUS_IN_GROUP));
    }
}
