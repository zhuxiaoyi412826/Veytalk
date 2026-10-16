package com.im.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.group.entity.GroupJoinRequest;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入群申请 Mapper。
 *
 * <p>所有对外查询都限定了状态或时间序列，避免调用方漏写条件把已通过/拒绝的申请也当待办列出。
 */
@Mapper
public interface GroupJoinRequestMapper extends BaseMapper<GroupJoinRequest> {

    /**
     * 取某群某人的申请行（任何状态），重新提交时需要复用同一行以避开唯一键冲突。
     */
    default GroupJoinRequest selectRow(Long groupId, Long userId) {
        return selectOne(Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getGroupId, groupId)
                .eq(GroupJoinRequest::getUserId, userId)
                .last("LIMIT 1"));
    }

    /**
     * 分页查某群的待审批申请，按提交时间倒序（最新的排前面，管理员一眼能看到刚刚提交的人）。
     */
    default Page<GroupJoinRequest> selectPendingPage(Page<GroupJoinRequest> page, Long groupId) {
        return selectPage(page, Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getGroupId, groupId)
                .eq(GroupJoinRequest::getStatus, GroupJoinRequest.STATUS_PENDING)
                .orderByDesc(GroupJoinRequest::getCreateTime)
                .orderByDesc(GroupJoinRequest::getId));
    }

    /**
     * 统计某群的待审批数，群设置面板上的红点角标靠这个。
     */
    default long countPending(Long groupId) {
        return selectCount(Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getGroupId, groupId)
                .eq(GroupJoinRequest::getStatus, GroupJoinRequest.STATUS_PENDING));
    }

    /**
     * 提交或重新提交：把已存在的申请行状态拉回待审批，同时更新留言 / 来源 / 提交时间（{@code create_time} 保持不变）。
     *
     * <p>刻意把 handleUser / handleTime 一并清空：上次拒绝留下的痕迹要抹掉，否则前端会看到
     * 「状态=待审批 + 处理人非空」的矛盾行。
     *
     * @return 影响行数
     */
    default int resetToPending(Long groupId, Long userId, String message, String source) {
        return update(null, Wrappers.<GroupJoinRequest>lambdaUpdate()
                .set(GroupJoinRequest::getStatus, GroupJoinRequest.STATUS_PENDING)
                .set(GroupJoinRequest::getMessage, message)
                .set(GroupJoinRequest::getSource, source)
                .setSql("handle_user_id = NULL")
                .setSql("handle_time = NULL")
                .eq(GroupJoinRequest::getGroupId, groupId)
                .eq(GroupJoinRequest::getUserId, userId));
    }

    /**
     * 审批：把待审批的申请置为通过或拒绝。
     *
     * <p>条件里带 {@code status = 0}：两个管理员同时点「通过」只会有一次生效，另一次返回 0，调用方按已处理做幂等。
     */
    default int handle(Long id, int status, Long handleUserId, LocalDateTime handleTime) {
        return update(null, Wrappers.<GroupJoinRequest>lambdaUpdate()
                .set(GroupJoinRequest::getStatus, status)
                .set(GroupJoinRequest::getHandleUserId, handleUserId)
                .set(GroupJoinRequest::getHandleTime, handleTime)
                .eq(GroupJoinRequest::getId, id)
                .eq(GroupJoinRequest::getStatus, GroupJoinRequest.STATUS_PENDING));
    }

    /**
     * 按 ID 取行，任何状态都能拿到（供审批前预查）。
     */
    default GroupJoinRequest selectByIdAny(Long id) {
        if (id == null) {
            return null;
        }
        return selectOne(Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getId, id)
                .last("LIMIT 1"));
    }

    /**
     * 群解散或删号时清掉某群的所有申请（预留，目前只在 C1 账号注销级联清理里会用到）。
     */
    default int deleteByGroupIds(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return 0;
        }
        return delete(Wrappers.<GroupJoinRequest>lambdaQuery().in(GroupJoinRequest::getGroupId, groupIds));
    }
}
