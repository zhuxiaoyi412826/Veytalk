package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.util.TextUtil;
import com.im.live.entity.LiveVideoCategory;
import com.im.live.mapper.LiveVideoCategoryMapper;
import com.im.live.service.LiveVideoCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频板块二级分类字典服务实现。
 *
 * <p>本表是「小而全」的字典（几十行封顶），读侧一律全量取回内存排序，不分页也不缓存：
 * 分类栏进视频板块才拉一次、变更极少，加一层缓存换来的复杂度和「后台改了前端不刷新」的
 * 不一致，远不值当——真要提速是前端进页面拉一次，不是服务端塞缓存。
 *
 * <p>唯一性只校验未删除行（{@code @TableLogic} 已把已删行挡在查询外，这里天然满足）：
 * 同名分类不允许并存，改名为已存在的名字同样拒绝。删除是逻辑删除，历史视频的 category
 * 文本不动——分类是「视图」不是「外键约束」，删字典行不该回头改别人的数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveVideoCategoryServiceImpl implements LiveVideoCategoryService {

    private static final int NAME_MAX = 32;

    private final LiveVideoCategoryMapper categoryMapper;

    @Override
    public List<String> enabledNames() {
        List<LiveVideoCategory> rows = categoryMapper.selectList(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getStatus, LiveVideoCategory.STATUS_ENABLED)
                .orderByAsc(LiveVideoCategory::getSort)
                .orderByAsc(LiveVideoCategory::getId));
        List<String> names = new ArrayList<>(rows.size());
        for (LiveVideoCategory row : rows) {
            names.add(row.getName());
        }
        return names;
    }

    @Override
    public List<LiveVideoCategory> listAll() {
        return categoryMapper.selectList(new LambdaQueryWrapper<LiveVideoCategory>()
                .orderByAsc(LiveVideoCategory::getSort)
                .orderByAsc(LiveVideoCategory::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LiveVideoCategory create(String name, Integer sort, Long operatorId) {
        String trimmed = normalizeName(name);
        assertNameUnique(trimmed, null);
        LiveVideoCategory category = new LiveVideoCategory();
        category.setName(trimmed);
        category.setSort(sort == null ? nextSort() : sort);
        category.setStatus(LiveVideoCategory.STATUS_ENABLED);
        categoryMapper.insert(category);
        log.info("[后台分类管理] operator={} 新增视频分类: name={}, sort={}", operatorId, trimmed, category.getSort());
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LiveVideoCategory update(Long id, String name, Integer sort, Integer status, Long operatorId) {
        LiveVideoCategory existing = categoryMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在或已被删除");
        }
        if (TextUtil.isNotBlank(name)) {
            String trimmed = normalizeName(name);
            assertNameUnique(trimmed, id);
            existing.setName(trimmed);
        }
        if (sort != null) {
            existing.setSort(sort);
        }
        if (status != null) {
            existing.setStatus(status == LiveVideoCategory.STATUS_DISABLED
                    ? LiveVideoCategory.STATUS_DISABLED : LiveVideoCategory.STATUS_ENABLED);
        }
        categoryMapper.updateById(existing);
        log.info("[后台分类管理] operator={} 修改视频分类 {}: name={}, sort={}, status={}",
                operatorId, id, existing.getName(), existing.getSort(), existing.getStatus());
        return existing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        LiveVideoCategory existing = categoryMapper.selectById(id);
        if (existing == null) {
            // 幂等：已删过的行再删一次不报错
            return;
        }
        categoryMapper.deleteById(id);
        log.info("[后台分类管理] operator={} 删除视频分类 {}: name={}（历史视频 category 不动）",
                operatorId, id, existing.getName());
    }

    /** trim + 非空 + 长度兜底到列宽，与 {@code LiveVideoServiceImpl#resolveCategory} 同一口径 */
    private String normalizeName(String name) {
        if (TextUtil.isBlank(name)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分类名称不能为空");
        }
        String trimmed = name.trim();
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX) : trimmed;
    }

    /** 未删除行内查重（excludeId 用于改名时放过自己这一行） */
    private void assertNameUnique(String name, Long excludeId) {
        Long count = categoryMapper.selectCount(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getName, name)
                .ne(excludeId != null, LiveVideoCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "已存在同名分类：" + name);
        }
    }

    /** 新分类默认排到最后：当前最大 sort + 1（空表从 1 起） */
    private int nextSort() {
        LiveVideoCategory last = categoryMapper.selectOne(new LambdaQueryWrapper<LiveVideoCategory>()
                .orderByDesc(LiveVideoCategory::getSort)
                .last("LIMIT 1"));
        return last == null || last.getSort() == null ? 1 : last.getSort() + 1;
    }
}
