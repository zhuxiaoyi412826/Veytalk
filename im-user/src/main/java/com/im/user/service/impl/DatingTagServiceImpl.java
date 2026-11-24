package com.im.user.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import com.im.user.dto.req.TagSaveRequest;
import com.im.user.dto.vo.TagCategoryVO;
import com.im.user.dto.vo.TagVO;
import com.im.user.entity.InterestTag;
import com.im.user.entity.UserTag;
import com.im.user.mapper.InterestTagMapper;
import com.im.user.mapper.UserTagMapper;
import com.im.user.service.DatingProfileService;
import com.im.user.service.DatingTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 兴趣标签实现。
 *
 * <p>与 {@code DatingProfileServiceImpl} 之间是单向依赖：本类注入资料服务用于重算完资料度，
 * 而资料服务只注入 {@link UserTagMapper} 取个数。反过来注成双向就是构造器循环依赖，
 * 应用会在启动那一步直接失败（照片墙与资料卡之间是同一个结，处理也一致）。
 *
 * <p>{@code im_tag} 与 {@code im_user_tag} 的两步读（先取绑定行、再按 id 批量取标签名）是刻意的：
 * 换成一条 JOIN 就得为两个入口各写一个 resultType，而这里两次都是走主键与唯一键的点查集合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatingTagServiceImpl implements DatingTagService {

    private final InterestTagMapper interestTagMapper;
    private final UserTagMapper userTagMapper;
    private final DatingProfileService datingProfileService;

    @Override
    public List<TagCategoryVO> library() {
        List<InterestTag> tags = interestTagMapper.selectList(Wrappers.<InterestTag>lambdaQuery()
                .eq(InterestTag::getStatus, InterestTag.STATUS_ENABLED)
                .orderByAsc(InterestTag::getSort)
                .orderByAsc(InterestTag::getId));
        // 全局按 sort 升序后用 LinkedHashMap 归组，分类顺序就自然等于「该分类最小的 sort」，
        // 不需要再写一条 GROUP BY MIN(sort) 去把运营排的次序翻译一遍
        Map<String, List<TagVO>> grouped = new LinkedHashMap<>();
        for (InterestTag tag : tags) {
            grouped.computeIfAbsent(category(tag), key -> new ArrayList<>()).add(toVO(tag, false));
        }
        List<TagCategoryVO> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<TagVO>> entry : grouped.entrySet()) {
            result.add(new TagCategoryVO(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Override
    public List<TagVO> listByUser(Long targetUserId) {
        if (targetUserId == null) {
            return List.of();
        }
        List<UserTag> rows = listRows(targetUserId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, InterestTag> dict = loadTags(rows.stream().map(UserTag::getTagId).toList());
        List<TagVO> result = new ArrayList<>(rows.size());
        for (UserTag row : rows) {
            InterestTag tag = dict.get(row.getTagId());
            if (tag == null) {
                // 标签已被后台删除：这一行取不出名字，跳过而不是报错——读路径不顺手改数据，
                // 至于这几行脏绑定，用户下次保存标签时会被自然清掉
                continue;
            }
            result.add(toVO(tag, isDisabled(tag)));
        }
        return result;
    }

    @Override
    public Map<Long, List<String>> namesByUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<UserTag> rows = userTagMapper.selectByUserIds(userIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, InterestTag> dict = loadTags(rows.stream().map(UserTag::getTagId).distinct().toList());
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (UserTag row : rows) {
            InterestTag tag = dict.get(row.getTagId());
            if (tag == null) {
                // 与 listByUser 同一口径：后台删掉的标签在读取时跳过而不报错，也不顺手清理
                continue;
            }
            result.computeIfAbsent(row.getUserId(), key -> new ArrayList<>()).add(tag.getName());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TagVO> save(Long userId, TagSaveRequest request) {
        List<Long> ids = normalize(request.getIds());
        BusinessException.throwIf(ids.size() > UserTag.MAX_SELECT, ResultCode.DATING_TAG_LIMIT, UserTag.MAX_SELECT);

        List<UserTag> rows = listRows(userId);
        Map<Long, UserTag> bound = new LinkedHashMap<>();
        for (UserTag row : rows) {
            bound.put(row.getTagId(), row);
        }
        Map<Long, InterestTag> dict = loadTags(ids);
        for (Long tagId : ids) {
            InterestTag tag = dict.get(tagId);
            // 已在用的停用标签允许保留，新选的不允许：否则后台一下架，用户只是改别的字段都会保存失败，
            // 而他必须在「丢掉这个兴趣」和「永远保存不了」之间二选一
            boolean acceptable = tag != null && (!isDisabled(tag) || bound.containsKey(tagId));
            BusinessException.throwUnless(acceptable, ResultCode.DATING_TAG_INVALID);
        }

        List<Long> toRemove = bound.keySet().stream().filter(tagId -> !ids.contains(tagId)).toList();
        if (!toRemove.isEmpty()) {
            userTagMapper.delete(Wrappers.<UserTag>lambdaQuery()
                    .eq(UserTag::getUserId, userId)
                    .in(UserTag::getTagId, toRemove));
            toRemove.forEach(tagId -> interestTagMapper.adjustUseCount(tagId, -1));
        }
        for (int i = 0; i < ids.size(); i++) {
            Long tagId = ids.get(i);
            if (bound.containsKey(tagId)) {
                continue;
            }
            UserTag row = new UserTag();
            row.setUserId(userId);
            row.setTagId(tagId);
            row.setSort(i);
            try {
                userTagMapper.insert(row);
            } catch (DuplicateKeyException e) {
                // 两个标签页同时保存时由 uk_user_tag 兜底：行已经在库里，那就是想要的状态，
                // 但不能因此再给 use_count 计一次人头
                log.debug("[兴趣标签] 并发命中唯一键，视为已绑定: userId={}, tagId={}", userId, tagId);
                continue;
            }
            interestTagMapper.adjustUseCount(tagId, 1);
        }
        // 新插入的行已经带着正确下标，只有留下来的旧行可能需要挪位；已经对上的不写，避免整表写放大
        Map<Long, Integer> target = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            target.put(ids.get(i), i);
        }
        for (UserTag row : listRows(userId)) {
            Integer want = target.get(row.getTagId());
            if (want == null || Objects.equals(row.getSort(), want)) {
                continue;
            }
            userTagMapper.update(null, Wrappers.<UserTag>lambdaUpdate()
                    .eq(UserTag::getId, row.getId())
                    .set(UserTag::getSort, want));
        }
        datingProfileService.recalcScore(userId);
        return listByUser(userId);
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /** 去 null、去重（保留首次出现的位置），空数组原样返回，表示清空。 */
    private List<Long> normalize(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private List<UserTag> listRows(Long userId) {
        return userTagMapper.selectList(Wrappers.<UserTag>lambdaQuery()
                .eq(UserTag::getUserId, userId)
                .orderByAsc(UserTag::getSort)
                .orderByAsc(UserTag::getId));
    }

    /**
     * 批量取标签定义。
     *
     * @return key 为 tagId；<b>已被逻辑删除的标签不会出现在结果里</b>，调用方按「取不到」处理
     */
    private Map<Long, InterestTag> loadTags(List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, InterestTag> result = new HashMap<>();
        for (InterestTag tag : interestTagMapper.selectBatchIds(tagIds)) {
            result.put(tag.getId(), tag);
        }
        return result;
    }

    private TagVO toVO(InterestTag tag, boolean disabled) {
        return TagVO.builder()
                .tagId(tag.getId())
                .name(tag.getName())
                .category(category(tag))
                .icon(tag.getIcon())
                .useCount(tag.getUseCount())
                .disabled(disabled)
                .build();
    }

    private String category(InterestTag tag) {
        return TextUtil.isBlank(tag.getCategory()) ? InterestTag.DEFAULT_CATEGORY : tag.getCategory().trim();
    }

    private boolean isDisabled(InterestTag tag) {
        Integer status = tag.getStatus();
        return status != null && status == InterestTag.STATUS_DISABLED;
    }
}
