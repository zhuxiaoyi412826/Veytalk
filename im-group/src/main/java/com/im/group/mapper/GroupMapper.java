package com.im.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.group.entity.Group;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 群组 Mapper。
 *
 * <p>所有对外查询都带 {@code status = 1}：解散后的群在业务上等同于不存在，
 * 但记录要留着供历史消息回溯，因此不能用逻辑删除来表达「已解散」。
 */
@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    /**
     * 查询未解散的群，解散或不存在都返回 {@code null}。
     */
    default Group selectNormalById(Long groupId) {
        if (groupId == null) {
            return null;
        }
        return selectOne(Wrappers.<Group>lambdaQuery()
                .eq(Group::getId, groupId)
                .eq(Group::getStatus, Group.STATUS_NORMAL)
                .last("LIMIT 1"));
    }

    /**
     * 批量查询未解散的群，供会话列表一次性回填群名群头像。
     */
    default List<Group> selectNormalByIds(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Group>lambdaQuery()
                .in(Group::getId, groupIds)
                .eq(Group::getStatus, Group.STATUS_NORMAL));
    }

    /**
     * 转让群主时同步群上的 {@code owner_id}。
     *
     * <p>条件里限定 {@code owner_id} 仍是原群主：两人同时转让时只有一个能改到行，
     * 返回 0 即表示群主已经换人，调用方据此中止本次转让而不是把 {@code owner_id} 覆写回去。
     *
     * @return 影响行数
     */
    default int updateOwner(Long groupId, Long oldOwnerId, Long newOwnerId) {
        return update(null, Wrappers.<Group>lambdaUpdate()
                .set(Group::getOwnerId, newOwnerId)
                .eq(Group::getId, groupId)
                .eq(Group::getOwnerId, oldOwnerId)
                .eq(Group::getStatus, Group.STATUS_NORMAL));
    }

    /**
     * 开关全员禁言。
     *
     * <p>不在条件里限定原值：重复开启应该幂等成功而不是报错，因此开关未变时也当作正常处理。
     *
     * @param muteAll 1 开启 0 关闭
     * @return 影响行数，0 表示群已解散或不存在
     */
    default int updateMuteAll(Long groupId, int muteAll) {
        return update(null, Wrappers.<Group>lambdaUpdate()
                .set(Group::getMuteAll, muteAll)
                .eq(Group::getId, groupId)
                .eq(Group::getStatus, Group.STATUS_NORMAL));
    }

    /**
     * 解散群：只改状态，成员行与历史消息都保留。
     *
     * <p>条件里带上 {@code status = 1}，两个人同时解散时只有一个能改到行，
     * 返回 0 即表示已被抢先解散，调用方据此做幂等处理。
     *
     * @return 影响行数
     */
    default int dismiss(Long groupId) {
        return update(null, Wrappers.<Group>lambdaUpdate()
                .set(Group::getStatus, Group.STATUS_DISMISSED)
                .eq(Group::getId, groupId)
                .eq(Group::getStatus, Group.STATUS_NORMAL));
    }
}
