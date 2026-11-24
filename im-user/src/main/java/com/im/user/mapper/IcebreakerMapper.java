package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.user.entity.Icebreaker;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 破冰文案字典 Mapper。
 *
 * <p>只有 {@link #increaseUseCount} 需要自己写 SQL：它是一条「读出来改完再写回」会丢计数的
 * 自增，与 {@code InterestTagMapper#adjustUseCount} 同一个理由。
 * 区别是这里不需要 {@code GREATEST(..., 0)}——使用次数只加不减，没有把它拖成负数的路径。
 */
@Mapper
public interface IcebreakerMapper extends BaseMapper<Icebreaker> {

    /**
     * 一次取走建议需要的全部文案：所有通用行（{@code tag_id = 0}）+ 绑在双方共有标签上的行。
     *
     * <p>不按 type 分三次查：三类文案来自同一张字典表、同一批 {@code WHERE}，
     * 分三次就是三条 SQL 拿一份数据，而分组在服务层做一次 {@code for} 就完了。
     *
     * <p>{@code sharedTagIds} 是<b>双方共有的那几个标签</b>，不是某一方的全部标签：
     * 「你也练健身？」这句话的前提是我也练，只按对方的标签挑会得到一句穿帮的开场白。
     *
     * <p>不写 {@code LIMIT}：每类的上限是产品口径（{@link Icebreaker#SUGGEST_GREETING_MAX} 三个常量），
     * 在内存里截比把三个上限写进 SQL 好改。
     */
    default List<Icebreaker> selectSuggestPool(Collection<Long> sharedTagIds) {
        boolean anyShared = sharedTagIds != null && !sharedTagIds.isEmpty();
        return selectList(Wrappers.<Icebreaker>lambdaQuery()
                .eq(Icebreaker::getStatus, Icebreaker.STATUS_ENABLED)
                .and(wrapper -> {
                    wrapper.eq(Icebreaker::getTagId, Icebreaker.TAG_ID_ANY);
                    if (anyShared) {
                        wrapper.or().in(Icebreaker::getTagId, sharedTagIds);
                    }
                })
                .orderByAsc(Icebreaker::getType)
                .orderByAsc(Icebreaker::getSort)
                .orderByAsc(Icebreaker::getId));
    }

    /**
     * 记一次「用户点了这条」。
     *
     * <p>条件里带上 {@code status = 1}：用户手上那份列表可能是几十秒前拉的，
     * 期间后台停用了这一条，此时计数不该再涨（运营看到的「停用前用过多少次」才是准的）。
     *
     * @return 影响行数，0 表示这条已停用或已删除
     */
    @Update("""
            UPDATE im_icebreaker
            SET use_count = use_count + 1
            WHERE id = #{id}
              AND status = 1
              AND deleted = 0
            """)
    int increaseUseCount(@Param("id") Long id);
}
