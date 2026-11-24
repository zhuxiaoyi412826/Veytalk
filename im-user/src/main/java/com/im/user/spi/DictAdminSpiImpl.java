package com.im.user.spi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.im.common.api.PageResult;
import com.im.common.api.ResultCode;
import com.im.common.domain.AdminIcebreakerDTO;
import com.im.common.domain.AdminTagDTO;
import com.im.common.enums.IcebreakerType;
import com.im.common.exception.BusinessException;
import com.im.common.spi.AdminDictSpi;
import com.im.common.util.TextUtil;
import com.im.user.entity.Icebreaker;
import com.im.user.entity.InterestTag;
import com.im.user.mapper.IcebreakerMapper;
import com.im.user.mapper.InterestTagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台字典契约的实现（{@link AdminDictSpi}）：兴趣标签库与破冰文案库。
 *
 * <p>类名带模块前缀的理由见 {@code AdminMetricsSpi} 接口注释。
 *
 * <p>本类是这两张表的<b>唯一写入方（除种子脚本外）</b>：用户端只有「从启用中的标签里选」
 * 与「读文案」两条路（见 {@code DatingTagService} / {@code IcebreakerService}），所以这里不需要
 * 与谁抢一致性，只需要保证改完的行仍然被那些读方解释得通——最典型的就是改名：
 * 关联表存的是 ID，名字现取，所以改名即刻生效且不需要回写任何一行 {@code im_user_tag}。
 *
 * <p>{@code use_count} 由绑定关系维护，本类<b>一个都不改</b>：后台新增一个标签时它是 0，
 * 删除一个标签时也不去清别人的计数（那些绑定行还在，只是标签隐身了）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictAdminSpiImpl implements AdminDictSpi {

    /** 单页上限，与其余后台契约同一口径 */
    private static final long MAX_PAGE_SIZE = 100;
    /** 关键词长度上限，防止一条超长 like 拖死全表扫 */
    private static final int KEYWORD_MAX = 50;

    /** 以下四档列宽与建表语句一一对应，超长一律截断而不是报错 */
    private static final int TAG_NAME_MAX = 32;
    private static final int TAG_CATEGORY_MAX = 16;
    private static final int TAG_ICON_MAX = 32;
    private static final int ICEBREAKER_CONTENT_MAX = 255;

    /** 绑定的标签已被删除时给占位文案：留空会让这条开场白看起来像通用文案，于是没人去修它 */
    private static final String TAG_GONE = "标签已删除";

    private final InterestTagMapper interestTagMapper;
    private final IcebreakerMapper icebreakerMapper;

    /* ==================== 兴趣标签 ==================== */

    @Override
    public List<AdminTagDTO> listTags(String category) {
        List<InterestTag> rows = interestTagMapper.selectList(Wrappers.<InterestTag>lambdaQuery()
                .eq(TextUtil.isNotBlank(category), InterestTag::getCategory, trimToNull(category))
                .orderByAsc(InterestTag::getCategory)
                .orderByAsc(InterestTag::getSort)
                .orderByAsc(InterestTag::getId));
        List<AdminTagDTO> dtos = new ArrayList<>(rows.size());
        for (InterestTag row : rows) {
            dtos.add(toTagDTO(row));
        }
        return dtos;
    }

    @Override
    public AdminTagDTO createTag(String name, String category, String icon, Integer sort, Long operatorId) {
        String tagName = requireTagName(name);
        // 重名要在写库前拦：MySQL 的 uk_name 也会拦，但那条会变成一个谁也读不懂的 500
        BusinessException.throwIf(existTagName(tagName, null), ResultCode.BAD_REQUEST, "标签名已存在：" + tagName);
        InterestTag row = new InterestTag();
        row.setName(tagName);
        row.setCategory(cut(category, TAG_CATEGORY_MAX, InterestTag.DEFAULT_CATEGORY));
        row.setIcon(cut(icon, TAG_ICON_MAX, null));
        row.setSort(sort == null ? nextTagSort(row.getCategory()) : sort);
        row.setStatus(InterestTag.STATUS_ENABLED);
        row.setUseCount(0);
        interestTagMapper.insert(row);
        log.info("[后台标签管理] operator={} 新增标签 {}: category={}", operatorId, row.getId(), row.getCategory());
        return toTagDTO(row);
    }

    @Override
    public AdminTagDTO updateTag(Long id, String name, String category, String icon, Integer sort, Integer status,
                                 Long operatorId) {
        InterestTag row = requireTag(id);
        InterestTag patch = new InterestTag();
        patch.setId(row.getId());
        if (TextUtil.isNotBlank(name)) {
            String tagName = requireTagName(name);
            if (!tagName.equals(row.getName())) {
                BusinessException.throwIf(existTagName(tagName, id), ResultCode.BAD_REQUEST, "标签名已存在：" + tagName);
                patch.setName(tagName);
            }
        }
        if (category != null) {
            patch.setCategory(cut(category, TAG_CATEGORY_MAX, InterestTag.DEFAULT_CATEGORY));
        }
        if (icon != null) {
            patch.setIcon(cut(icon, TAG_ICON_MAX, null));
        }
        if (sort != null) {
            patch.setSort(sort);
        }
        if (status != null) {
            BusinessException.throwIf(status != InterestTag.STATUS_ENABLED && status != InterestTag.STATUS_DISABLED,
                    ResultCode.BAD_REQUEST, "状态只能是 0 或 1");
            patch.setStatus(status);
        }
        interestTagMapper.updateById(patch);
        log.info("[后台标签管理] operator={} 修改标签 {}: name={}, category={}, status={}",
                operatorId, id, patch.getName(), patch.getCategory(), patch.getStatus());
        return toTagDTO(interestTagMapper.selectById(id));
    }

    @Override
    public void deleteTag(Long id, Long operatorId) {
        InterestTag row = interestTagMapper.selectById(id);
        if (row == null) {
            // 幂等：已删过的行再删一次不报错
            return;
        }
        interestTagMapper.deleteById(id);
        // 不动 im_user_tag，也不调 use_count：见类注释
        log.info("[后台标签管理] operator={} 删除标签 {}: name={}, useCount={}",
                operatorId, id, row.getName(), row.getUseCount());
    }

    /* ==================== 破冰文案 ==================== */

    @Override
    public PageResult<AdminIcebreakerDTO> pageIcebreakers(Integer type, Long tagId, String keyword,
                                                          long current, long size) {
        String kw = cut(keyword, KEYWORD_MAX, null);
        Page<Icebreaker> result = icebreakerMapper.selectPage(
                new Page<>(Math.max(1, current), Math.min(Math.max(1, size), MAX_PAGE_SIZE)),
                Wrappers.<Icebreaker>lambdaQuery()
                        .eq(type != null, Icebreaker::getType, type)
                        .eq(tagId != null, Icebreaker::getTagId, tagId)
                        .like(TextUtil.isNotBlank(kw), Icebreaker::getContent, kw)
                        .orderByAsc(Icebreaker::getType)
                        .orderByAsc(Icebreaker::getSort)
                        .orderByAsc(Icebreaker::getId));
        List<Icebreaker> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResult.empty(result.getCurrent(), result.getSize());
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        for (Icebreaker row : rows) {
            if (row.getTagId() != null && row.getTagId() != Icebreaker.TAG_ID_ANY) {
                tagIds.add(row.getTagId());
            }
        }
        Map<Long, String> tagNames = tagNames(tagIds);
        List<AdminIcebreakerDTO> dtos = new ArrayList<>(rows.size());
        for (Icebreaker row : rows) {
            dtos.add(toIcebreakerDTO(row, tagNames));
        }
        return PageResult.of(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminIcebreakerDTO createIcebreaker(Integer type, Long tagId, String content, Integer sort, Long operatorId) {
        IcebreakerType checked = requireType(type);
        String text = requireContent(content);
        long bound = requireBinding(checked, tagId);
        Icebreaker row = new Icebreaker();
        row.setType(checked.getCode());
        row.setTagId(bound);
        row.setContent(text);
        row.setSort(sort == null ? nextIcebreakerSort(checked.getCode()) : sort);
        row.setStatus(Icebreaker.STATUS_ENABLED);
        row.setUseCount(0);
        icebreakerMapper.insert(row);
        log.info("[后台破冰管理] operator={} 新增文案 {}: type={}, tagId={}",
                operatorId, row.getId(), row.getType(), row.getTagId());
        return toIcebreakerDTO(row, tagNames(Set.of(bound)));
    }

    @Override
    public AdminIcebreakerDTO updateIcebreaker(Long id, Integer type, Long tagId, String content, Integer sort,
                                               Integer status, Long operatorId) {
        Icebreaker row = requireIcebreaker(id);
        IcebreakerType nextType = type == null ? requireType(row.getType()) : requireType(type);
        Icebreaker patch = new Icebreaker();
        patch.setId(row.getId());
        patch.setType(nextType.getCode());
        if (tagId != null) {
            patch.setTagId(requireBinding(nextType, tagId));
        }
        // 改类型时不顺手清掉旧的绑定：绑着的标签对打招呼模板与话题卡是无害冗余
        // （选文案时只看通用行与共有标签行，绑错了最多这一条不参与挑选），
        // 而这里一清，运营把类型改回来还得重选一次标签
        if (TextUtil.isNotBlank(content)) {
            patch.setContent(requireContent(content));
        }
        if (sort != null) {
            patch.setSort(sort);
        }
        if (status != null) {
            BusinessException.throwIf(status != Icebreaker.STATUS_ENABLED && status != Icebreaker.STATUS_DISABLED,
                    ResultCode.BAD_REQUEST, "状态只能是 0 或 1");
            patch.setStatus(status);
        }
        icebreakerMapper.updateById(patch);
        log.info("[后台破冰管理] operator={} 修改文案 {}: type={}, status={}",
                operatorId, id, patch.getType(), patch.getStatus());
        Icebreaker latest = icebreakerMapper.selectById(id);
        return toIcebreakerDTO(latest, tagNames(boundIds(latest)));
    }

    @Override
    public void deleteIcebreaker(Long id, Long operatorId) {
        Icebreaker row = icebreakerMapper.selectById(id);
        if (row == null) {
            return;
        }
        icebreakerMapper.deleteById(id);
        log.info("[后台破冰管理] operator={} 删除文案 {}: type={}, useCount={}",
                operatorId, id, row.getType(), row.getUseCount());
    }

    /* ==================== 校验与组装 ==================== */

    private InterestTag requireTag(Long id) {
        BusinessException.throwIf(id == null, ResultCode.BAD_REQUEST, "标签 ID 不能为空");
        InterestTag row = interestTagMapper.selectById(id);
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "标签不存在或已被删除");
        return row;
    }

    private Icebreaker requireIcebreaker(Long id) {
        BusinessException.throwIf(id == null, ResultCode.BAD_REQUEST, "文案 ID 不能为空");
        Icebreaker row = icebreakerMapper.selectById(id);
        BusinessException.throwIf(row == null, ResultCode.NOT_FOUND, "文案不存在或已被删除");
        return row;
    }

    private static String requireTagName(String name) {
        String text = trimToNull(name);
        BusinessException.throwIf(text == null, ResultCode.BAD_REQUEST, "标签名不能为空");
        return text.length() > TAG_NAME_MAX ? text.substring(0, TAG_NAME_MAX) : text;
    }

    private static String requireContent(String content) {
        String text = trimToNull(content);
        BusinessException.throwIf(text == null, ResultCode.BAD_REQUEST, "文案内容不能为空");
        BusinessException.throwIf(text.length() > ICEBREAKER_CONTENT_MAX,
                ResultCode.BAD_REQUEST, "文案最多 " + ICEBREAKER_CONTENT_MAX + " 字");
        return text;
    }

    private static IcebreakerType requireType(Integer code) {
        IcebreakerType type = IcebreakerType.of(code);
        BusinessException.throwIf(type == null, ResultCode.BAD_REQUEST, "未知的文案类型：" + code);
        return type;
    }

    /**
     * 解析并校验绑定标签，返回要落库的 {@code tag_id}。
     *
     * <p>标签开场白<b>必须</b>绑一个存在的标签：这一类的整条链路是「按双方共有的标签挑文案」，
     * 没绑标签的开场白既进不了候选池（{@code IcebreakerMapper#selectSuggestPool} 只取通用行与
     * 共有标签行），又会让 {@code {tag}} 替换不出来，最终就是一句永远发不出去的死文案。
     */
    private long requireBinding(IcebreakerType type, Long tagId) {
        long bound = tagId == null ? Icebreaker.TAG_ID_ANY : tagId;
        if (bound == Icebreaker.TAG_ID_ANY) {
            BusinessException.throwIf(type == IcebreakerType.TAG_OPENER,
                    ResultCode.BAD_REQUEST, "标签开场白必须绑定一个兴趣标签");
            return Icebreaker.TAG_ID_ANY;
        }
        InterestTag tag = interestTagMapper.selectById(bound);
        BusinessException.throwIf(tag == null, ResultCode.BAD_REQUEST, "绑定的标签不存在：" + bound);
        return bound;
    }

    /** 同名判断只在未删除的行内进行（逻辑删的行让 {@code uk_name} 也拦不住，但字典里不该出现两个同名）。 */
    private boolean existTagName(String name, Long excludeId) {
        return interestTagMapper.exists(Wrappers.<InterestTag>lambdaQuery()
                .eq(InterestTag::getName, name)
                .ne(excludeId != null, InterestTag::getId, excludeId));
    }

    /** 新标签排到本分类最后：不这么处理时后台新增的一律是 {@code sort=0}，全挤在分类最前面。 */
    private int nextTagSort(String category) {
        List<InterestTag> rows = interestTagMapper.selectList(Wrappers.<InterestTag>lambdaQuery()
                .select(InterestTag::getSort)
                .eq(InterestTag::getCategory, category)
                .orderByDesc(InterestTag::getSort)
                .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getSort() == null ? 0 : rows.get(0).getSort() + 1;
    }

    private int nextIcebreakerSort(Integer type) {
        List<Icebreaker> rows = icebreakerMapper.selectList(Wrappers.<Icebreaker>lambdaQuery()
                .select(Icebreaker::getSort)
                .eq(Icebreaker::getType, type)
                .orderByDesc(Icebreaker::getSort)
                .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getSort() == null ? 0 : rows.get(0).getSort() + 1;
    }

    /** 一次取回这批绑定 ID 的名字，通用行（0）不在结果里。 */
    private Map<Long, String> tagNames(Collection<Long> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(tagIds.size());
        for (InterestTag tag : interestTagMapper.selectList(Wrappers.<InterestTag>lambdaQuery()
                .select(InterestTag::getId, InterestTag::getName)
                .in(InterestTag::getId, tagIds))) {
            result.put(tag.getId(), tag.getName());
        }
        return result;
    }

    private static Set<Long> boundIds(Icebreaker row) {
        return row.getTagId() == null || row.getTagId() == Icebreaker.TAG_ID_ANY
                ? Set.of() : Set.of(row.getTagId());
    }

    private AdminTagDTO toTagDTO(InterestTag row) {
        return AdminTagDTO.builder()
                .id(row.getId())
                .name(row.getName())
                .category(row.getCategory())
                .icon(row.getIcon())
                .sort(row.getSort())
                .status(row.getStatus())
                .useCount(row.getUseCount())
                .createTime(row.getCreateTime())
                .build();
    }

    private AdminIcebreakerDTO toIcebreakerDTO(Icebreaker row, Map<Long, String> tagNames) {
        boolean bound = row.getTagId() != null && row.getTagId() != Icebreaker.TAG_ID_ANY;
        return AdminIcebreakerDTO.builder()
                .id(row.getId())
                .type(row.getType())
                .tagId(row.getTagId())
                .tagName(bound ? tagNames.getOrDefault(row.getTagId(), TAG_GONE) : null)
                .content(row.getContent())
                .sort(row.getSort())
                .status(row.getStatus())
                .useCount(row.getUseCount())
                .createTime(row.getCreateTime())
                .build();
    }

    /**
     * 截断到列宽，空白落到缺省值。
     *
     * <p>截断而不报错：字典类字段超长多半是粘贴带出来的空格与全角符号，
     * 为这个弹一个错误框只会让人再粘一次。
     */
    private static String cut(String value, int max, String fallback) {
        String text = trimToNull(value);
        if (text == null) {
            return fallback;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String trimToNull(String value) {
        return TextUtil.isBlank(value) ? null : value.trim();
    }
}
