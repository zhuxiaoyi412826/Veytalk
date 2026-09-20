package com.im.friend.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.domain.UserBriefDTO;
import com.im.common.enums.FriendStatus;
import com.im.common.exception.BusinessException;
import com.im.common.spi.UserQuerySpi;
import com.im.common.util.TextUtil;
import com.im.friend.convert.FriendConvert;
import com.im.friend.dto.vo.FriendVO;
import com.im.friend.entity.Friend;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.service.FriendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 好友关系服务实现。
 *
 * <p>列表刻意一次性取出全部关系行再在内存中按关键字过滤：好友的昵称与账号存放在
 * {@code im_user}（属 im-user 模块），跨模块只能走 {@link UserQuerySpi} 而不能 JOIN。
 * 单用户好友量级有限，一次 {@code listByIds} 批量补齐资料即可，不会产生 N+1。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    /** 备注与分组名的长度上限，与 DDL 中的 VARCHAR(32) 对齐 */
    private static final int NAME_MAX_LENGTH = 32;

    private final FriendMapper friendMapper;
    private final UserQuerySpi userQuerySpi;

    @Override
    public List<FriendVO> list(Long userId, String keyword) {
        List<Friend> relations = friendMapper.selectByUserId(userId);
        if (relations.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> friendIds = relations.stream().map(Friend::getFriendId).distinct().toList();
        Map<Long, UserBriefDTO> peers = userQuerySpi.listByIds(friendIds);

        String normalized = TextUtil.isBlank(keyword) ? null : keyword.trim().toLowerCase();
        List<FriendVO> result = new ArrayList<>(relations.size());
        for (Friend relation : relations) {
            FriendVO vo = FriendConvert.toVO(relation, peers.get(relation.getFriendId()));
            if (normalized == null || matches(vo, normalized)) {
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    public List<FriendVO> blacklist(Long userId) {
        // 复用好友列表的一次取数，只过滤出拉黑态的行；黑名单量级远小于好友数，不会退化成全表
        List<Friend> blocked = friendMapper.selectByUserId(userId).stream()
                .filter(Friend::isBlocked)
                .toList();
        if (blocked.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> friendIds = blocked.stream().map(Friend::getFriendId).distinct().toList();
        Map<Long, UserBriefDTO> peers = userQuerySpi.listByIds(friendIds);
        return blocked.stream()
                .map(relation -> FriendConvert.toVO(relation, peers.get(relation.getFriendId())))
                .toList();
    }

    @Override
    public List<String> listGroups(Long userId) {
        List<String> groups = new ArrayList<>(friendMapper.selectByUserId(userId).stream()
                .map(relation -> TextUtil.isBlank(relation.getGroupName())
                        ? Friend.DEFAULT_GROUP : relation.getGroupName())
                .distinct()
                .sorted()
                .toList());
        // 默认分组固定置顶，与前端分组栏的展示习惯一致
        if (groups.remove(Friend.DEFAULT_GROUP)) {
            groups.add(0, Friend.DEFAULT_GROUP);
        }
        return groups;
    }

    @Override
    public FriendVO detail(Long userId, Long friendId) {
        Friend relation = requireRelation(userId, friendId);
        return FriendConvert.toVO(relation, userQuerySpi.getById(friendId));
    }

    @Override
    public void updateRemark(Long userId, Long friendId, String remark) {
        Friend relation = requireRelation(userId, friendId);
        // 备注允许清空，必须用 UpdateWrapper.set 显式写入 null：
        // 若改走实体 updateById，MyBatis-Plus 默认的 NOT_NULL 策略会把 null 字段整个跳过，清不掉旧备注
        String cleaned = TextUtil.isBlank(remark) ? null : TextUtil.sanitize(remark.trim(), NAME_MAX_LENGTH);
        friendMapper.update(null, Wrappers.<Friend>lambdaUpdate()
                .set(Friend::getRemark, cleaned)
                .eq(Friend::getId, relation.getId()));
        log.info("[好友备注] userId={}, friendId={}, remark={}", userId, friendId, cleaned);
    }

    @Override
    public void updateGroup(Long userId, Long friendId, String groupName) {
        Friend relation = requireRelation(userId, friendId);
        BusinessException.throwIf(TextUtil.isBlank(groupName), ResultCode.BAD_REQUEST, "分组名不能为空");
        String cleaned = TextUtil.sanitize(groupName.trim(), NAME_MAX_LENGTH);
        friendMapper.update(null, Wrappers.<Friend>lambdaUpdate()
                .set(Friend::getGroupName, cleaned)
                .eq(Friend::getId, relation.getId()));
        log.info("[好友分组] userId={}, friendId={}, groupName={}", userId, friendId, cleaned);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long friendId) {
        requireRelation(userId, friendId);
        int rows = friendMapper.deleteBoth(userId, friendId);
        // 会话与历史消息保留，只解除关系；对方那一行也一并删除，避免留下单向残留
        log.info("[删除好友] userId={}, friendId={}, 删除关系行数={}", userId, friendId, rows);
    }

    @Override
    public void block(Long userId, Long friendId) {
        Friend relation = requireRelation(userId, friendId);
        BusinessException.throwIf(relation.isBlocked(), ResultCode.FRIEND_ALREADY_BLOCKED);
        updateStatus(relation.getId(), FriendStatus.BLOCKED);
        // 拉黑是单向的：只改我持有的这一行，对方的好友列表里我依然是正常好友
        log.info("[拉黑好友] userId={}, friendId={}", userId, friendId);
    }

    @Override
    public void unblock(Long userId, Long friendId) {
        Friend relation = requireRelation(userId, friendId);
        BusinessException.throwUnless(relation.isBlocked(), ResultCode.BAD_REQUEST, "对方不在你的黑名单中");
        updateStatus(relation.getId(), FriendStatus.NORMAL);
        log.info("[取消拉黑] userId={}, friendId={}", userId, friendId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindRelation(Long userA, Long userB) {
        restoreOrInsert(userA, userB);
        restoreOrInsert(userB, userA);
    }

    @Override
    public Friend requireRelation(Long userId, Long friendId) {
        BusinessException.throwIf(userId == null || friendId == null, ResultCode.BAD_REQUEST, "好友 ID 不能为空");
        Friend relation = friendMapper.selectRelation(userId, friendId);
        BusinessException.throwIf(relation == null, ResultCode.FRIEND_NOT_FOUND);
        return relation;
    }

    /**
     * 插入一个方向的关系行；若已存在残留行（曾拉黑或数据不一致）则只把状态恢复为正常，
     * 保留原有的备注与分组，避免用户重新加好友后丢失自己设置过的备注。
     */
    private void restoreOrInsert(Long userId, Long friendId) {
        Friend existing = friendMapper.selectRelation(userId, friendId);
        if (existing != null) {
            updateStatus(existing.getId(), FriendStatus.NORMAL);
            return;
        }
        Friend relation = new Friend();
        relation.setUserId(userId);
        relation.setFriendId(friendId);
        relation.setGroupName(Friend.DEFAULT_GROUP);
        relation.setStatus(FriendStatus.NORMAL.getCode());
        try {
            friendMapper.insert(relation);
        } catch (DuplicateKeyException e) {
            // 双方同时同意彼此的申请时，唯一键 uk_user_friend 兜底，转为状态恢复
            log.warn("[好友关系] 并发插入命中唯一键，转为状态恢复: userId={}, friendId={}", userId, friendId);
            friendMapper.update(null, Wrappers.<Friend>lambdaUpdate()
                    .set(Friend::getStatus, FriendStatus.NORMAL.getCode())
                    .eq(Friend::getUserId, userId)
                    .eq(Friend::getFriendId, friendId));
        }
    }

    private void updateStatus(Long relationId, FriendStatus status) {
        friendMapper.update(null, Wrappers.<Friend>lambdaUpdate()
                .set(Friend::getStatus, status.getCode())
                .eq(Friend::getId, relationId));
    }

    /**
     * 关键字命中判定：备注、展示名、昵称、账号任一包含即算命中，大小写不敏感。
     */
    private boolean matches(FriendVO vo, String normalizedKeyword) {
        return contains(vo.getRemark(), normalizedKeyword)
                || contains(vo.getDisplayName(), normalizedKeyword)
                || contains(vo.getNickname(), normalizedKeyword)
                || contains(vo.getUsername(), normalizedKeyword);
    }

    private boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase().contains(normalizedKeyword);
    }
}
