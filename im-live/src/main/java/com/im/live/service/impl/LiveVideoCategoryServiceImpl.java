package com.im.live.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.api.ResultCode;
import com.im.common.enums.WorkBoardType;
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
 * 板块二级分类字典服务实现（一张表吃 video / image / music / novel 四个板块）。
 *
 * <p>本表是「小而全」的字典（几十行封顶），读侧一律全量取回内存排序，不分页也不缓存：
 * 分类栏进板块才拉一次、变更极少，加一层缓存换来的复杂度和「后台改了前端不刷新」的
 * 不一致，远不值当——真要提速是前端进页面拉一次，不是服务端塞缓存。
 *
 * <p>三处校验都按板块收窄，少一处就是 bug：
 * <ul>
 *   <li>同名校验限定在<b>同板块未删除行</b>（图片的「综合」不能挡住小说的「综合」）；</li>
 *   <li>{@code sort} 起点取<b>本板块</b>最大 sort，否则四个板块会互相把新分类挤到列表末尾；</li>
 *   <li>改与删都要比对行自身的 type：{@code /api/admin/work/{type}/category} 的 {@code type} 是
 *       调用方给的，只有对应板块权限的人能进那一屏，<b>不比对就成了跨板块改字典的后门</b>。</li>
 * </ul>
 *
 * <p>删除是逻辑删除，历史作品的 category 文本不动——分类是「视图」不是「外键约束」，
 * 删字典行不该回头改别人的数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveVideoCategoryServiceImpl implements LiveVideoCategoryService {

    private static final int NAME_MAX = 32;

    private final LiveVideoCategoryMapper categoryMapper;

    @Override
    public List<String> enabledNames() {
        return enabledNames(WorkBoardType.VIDEO.getCode());
    }

    @Override
    public List<String> enabledNames(String type) {
        String board = requireBoard(type);
        List<LiveVideoCategory> rows = categoryMapper.selectList(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getType, board)
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
    public List<LiveVideoCategory> listAll(String type) {
        String board = requireBoard(type);
        return categoryMapper.selectList(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getType, board)
                .orderByAsc(LiveVideoCategory::getSort)
                .orderByAsc(LiveVideoCategory::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LiveVideoCategory create(String type, String name, Integer sort, Long operatorId) {
        String board = requireBoard(type);
        String trimmed = normalizeName(name);
        assertNameUnique(board, trimmed, null);
        LiveVideoCategory category = new LiveVideoCategory();
        // 板块一旦落库就不允许再改（见 update 的注释），所以在这里定死
        category.setType(board);
        category.setName(trimmed);
        category.setSort(sort == null ? nextSort(board) : sort);
        category.setStatus(LiveVideoCategory.STATUS_ENABLED);
        categoryMapper.insert(category);
        log.info("[后台分类管理] operator={} 新增 {} 分类: name={}, sort={}",
                operatorId, board, trimmed, category.getSort());
        return category;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LiveVideoCategory update(String type, Long id, String name, Integer sort, Integer status, Long operatorId) {
        LiveVideoCategory existing = requireRow(type, id);
        if (TextUtil.isNotBlank(name)) {
            String trimmed = normalizeName(name);
            assertNameUnique(existing.getType(), trimmed, id);
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
        log.info("[后台分类管理] operator={} 修改 {} 分类 {}: name={}, sort={}, status={}",
                operatorId, existing.getType(), id, existing.getName(), existing.getSort(), existing.getStatus());
        return existing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String type, Long id, Long operatorId) {
        String board = requireBoard(type);
        LiveVideoCategory existing = categoryMapper.selectById(id);
        if (existing == null) {
            // 幂等：已删过的行再删一次不报错
            return;
        }
        // 与 update 同一道跨板块闸门；不存在与不属于本板块也给同一个错，不泄露「这个 ID 是别的板块的」
        if (!board.equals(existing.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在或已被删除");
        }
        categoryMapper.deleteById(id);
        log.info("[后台分类管理] operator={} 删除 {} 分类 {}: name={}（历史作品 category 不动）",
                operatorId, board, id, existing.getName());
    }

    /**
     * 板块码白名单：只认 {@link WorkBoardType} 里的四个 code。
     *
     * <p>不认就拒，而不是默默当 {@code video}：一个拼错的 type 如果落到某个默认板块，
     * 后台就会出现「我在图片屏加了个分类，怎么跑到视频栏里了」。
     */
    private String requireBoard(String type) {
        WorkBoardType board = WorkBoardType.of(type);
        if (board == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知的板块：" + type);
        }
        return board.getCode();
    }

    /** 取行并校验板块归属，不存在或不属于该板块都回 NOT_FOUND */
    private LiveVideoCategory requireRow(String type, Long id) {
        String board = requireBoard(type);
        LiveVideoCategory existing = categoryMapper.selectById(id);
        if (existing == null || !board.equals(existing.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在或已被删除");
        }
        return existing;
    }

    /** trim + 非空 + 长度兜底到列宽，与 {@code WorkRules#category} 同一口径 */
    private String normalizeName(String name) {
        if (TextUtil.isBlank(name)) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "分类名称不能为空");
        }
        String trimmed = name.trim();
        return trimmed.length() > NAME_MAX ? trimmed.substring(0, NAME_MAX) : trimmed;
    }

    /** 未删除行内查重，<b>限定同板块</b>（excludeId 用于改名时放过自己这一行） */
    private void assertNameUnique(String type, String name, Long excludeId) {
        Long count = categoryMapper.selectCount(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getType, type)
                .eq(LiveVideoCategory::getName, name)
                .ne(excludeId != null, LiveVideoCategory::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "已存在同名分类：" + name);
        }
    }

    /** 新分类默认排到<b>本板块</b>最后：当前最大 sort + 1（该板块无行时从 1 起） */
    private int nextSort(String type) {
        LiveVideoCategory last = categoryMapper.selectOne(new LambdaQueryWrapper<LiveVideoCategory>()
                .eq(LiveVideoCategory::getType, type)
                .orderByDesc(LiveVideoCategory::getSort)
                .last("LIMIT 1"));
        return last == null || last.getSort() == null ? 1 : last.getSort() + 1;
    }
}
