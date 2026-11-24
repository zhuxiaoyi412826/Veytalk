package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MomentMedia;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 动态附件 Mapper。
 *
 * <p>这张表只有两种读法：按一条动态取它的九宫格，以及按一批动态 ID 一次取回整屏的附件。
 * 后者是 Feed 的必需品——一屏十几条动态逐条查附件就是十几次往返，而 {@code idx_moment_sort}
 * 本来就是为 {@code moment_id IN (...)} 这种批量读准备的。
 */
@Mapper
public interface MomentMediaMapper extends BaseMapper<MomentMedia> {

    /** 一条动态的附件，按 {@code (sort, id)} 升序；首行兼作卡片缩略图。 */
    default List<MomentMedia> selectByMomentId(Long momentId) {
        if (momentId == null) {
            return List.of();
        }
        return selectList(Wrappers.<MomentMedia>lambdaQuery()
                .eq(MomentMedia::getMomentId, momentId)
                .orderByAsc(MomentMedia::getSort)
                .orderByAsc(MomentMedia::getId));
    }

    /**
     * 一批动态的附件，按 {@code (moment_id, sort, id)} 升序。
     *
     * <p>行已经排好序，所以服务层按 {@code moment_id} 分组时不需要再排一次；
     * 分组用 {@code LinkedHashMap} 保序，Feed 上每条动态的九宫格顺序才是发布时定的那个。
     */
    default List<MomentMedia> selectByMomentIds(Collection<Long> momentIds) {
        if (momentIds == null || momentIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<MomentMedia>lambdaQuery()
                .in(MomentMedia::getMomentId, momentIds)
                .orderByAsc(MomentMedia::getMomentId)
                .orderByAsc(MomentMedia::getSort)
                .orderByAsc(MomentMedia::getId));
    }

    /**
     * 随动态一起删除：逻辑删（{@code deleted = 1}）。
     *
     * <p>{@code im_file} 那边一行都不动：秒传命中时同一份字节可能正被别的记录引用，
     * 而文件的生命周期由文件模块自己管（与 {@code UserPhoto} 删除时同一口径）。
     */
    default int deleteByMomentId(Long momentId) {
        return delete(Wrappers.<MomentMedia>lambdaQuery()
                .eq(MomentMedia::getMomentId, momentId));
    }
}
