package com.im.group.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.group.entity.GroupRobot;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 群机器人 Mapper。
 *
 * <p>删除走逻辑删除（{@code BaseEntity} 的 {@code @TableLogic}），因此这里的查询会自动追加
 * {@code deleted = 0} 条件：软删的机器人 token 不再命中，历史消息里的身份快照不受影响。
 */
@Mapper
public interface GroupRobotMapper extends BaseMapper<GroupRobot> {

    /**
     * Webhook 认证：按 token 反查机器人。逻辑删除会自动排除已删除的行。
     */
    default GroupRobot selectByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return selectOne(Wrappers.<GroupRobot>lambdaQuery()
                .eq(GroupRobot::getWebhookToken, token)
                .last("LIMIT 1"));
    }

    /**
     * 某群全部机器人，按创建时间倒序（新建的排前面）。
     */
    default List<GroupRobot> listByGroup(Long groupId) {
        return selectList(Wrappers.<GroupRobot>lambdaQuery()
                .eq(GroupRobot::getGroupId, groupId)
                .orderByDesc(GroupRobot::getCreateTime)
                .orderByDesc(GroupRobot::getId));
    }

    /**
     * 某群机器人数量，用于建机器人时做数量上限校验。
     */
    default long countByGroup(Long groupId) {
        return selectCount(Wrappers.<GroupRobot>lambdaQuery().eq(GroupRobot::getGroupId, groupId));
    }
}
