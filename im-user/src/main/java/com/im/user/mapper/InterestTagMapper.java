package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.im.user.entity.InterestTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 兴趣标签库 Mapper。
 *
 * <p>只有 {@link #adjustUseCount} 需要自己写 SQL，其余走 MyBatis-Plus 的通用条件构造器。
 */
@Mapper
public interface InterestTagMapper extends BaseMapper<InterestTag> {

    /**
     * 增减「多少个用户在用」这个冗余计数。
     *
     * <p>用一条 UPDATE 而不是读出来改完再写回：两个人同时选中同一个标签时，读-改-写会丢掉一次计数。
     * <p>{@code GREATEST(..., 0)} 是必需的：计数漂移（例如后台直接清过 {@code im_user_tag} 的行）
     * 会让减法把 {@code use_count} 拖成负数，而负数会一路排到「热门标签」的最前面。
     *
     * @param delta +1 有人新增选用，-1 有人取消
     * @return 影响行数，0 表示标签已被删除（此时不该让绑定失败，调用方忽略即可）
     */
    @Update("""
            UPDATE im_tag
            SET use_count = GREATEST(use_count + #{delta}, 0)
            WHERE id = #{tagId}
            """)
    int adjustUseCount(@Param("tagId") Long tagId, @Param("delta") int delta);
}
