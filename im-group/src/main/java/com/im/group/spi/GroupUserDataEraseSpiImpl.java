package com.im.group.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.spi.UserDataEraseSpi;
import com.im.group.entity.GroupJoinRequest;
import com.im.group.entity.GroupMember;
import com.im.group.entity.GroupRobot;
import com.im.group.mapper.GroupJoinRequestMapper;
import com.im.group.mapper.GroupMemberMapper;
import com.im.group.mapper.GroupRobotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账号注销时清除 im-group 持有的用户数据：群成员行、入群申请行、以及本人创建的群机器人配置。
 *
 * <p>群主体 {@code im_group} 与其 {@code owner_id} 不做处理：一个群承载的是其余成员的共享资产，
 * 不能因一人销号而解散；销号后群主 ID 指向的是已匿名化的假名行，不再指向可识别的个人。
 * 机器人是本人配置的自动化接入，属本人数据，故一并删除（逻辑删除，token 立即失效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupUserDataEraseSpiImpl implements UserDataEraseSpi {

    private final GroupMemberMapper groupMemberMapper;
    private final GroupJoinRequestMapper groupJoinRequestMapper;
    private final GroupRobotMapper groupRobotMapper;

    @Override
    public void eraseUserData(Long userId) {
        if (userId == null) {
            return;
        }
        int members = groupMemberMapper.delete(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getUserId, userId));
        int joinRequests = groupJoinRequestMapper.delete(Wrappers.<GroupJoinRequest>lambdaQuery()
                .eq(GroupJoinRequest::getUserId, userId));
        int robots = groupRobotMapper.delete(Wrappers.<GroupRobot>lambdaQuery()
                .eq(GroupRobot::getCreatorId, userId));
        log.info("[账号注销] im-group 清除 userId={}：群成员 {} 行、入群申请 {} 行、机器人 {} 个",
                userId, members, joinRequests, robots);
    }
}
