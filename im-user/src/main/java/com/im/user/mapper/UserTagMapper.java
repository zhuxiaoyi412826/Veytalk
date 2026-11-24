package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.user.entity.UserTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 用户兴趣标签绑定 Mapper。
 *
 * <p>{@code countByUserId} 是完资料度里标签那一档的唯一取数入口。它原先住在
 * {@code DatingStatMapper}（一张跨表计数表）里，标签功能落地后迁到这里：
 * <b>读 {@code im_user_tag} 的路径只留一套</b>，否则将来加一个「标签停用不算分」的规则
 * 就得记得改两个地方，而忘记的那处会安静地继续给分。
 */
@Mapper
public interface UserTagMapper extends BaseMapper<UserTag> {

    /** 单个用户已选的标签数（本表物理删除，无 {@code deleted} 列，行在就是选中）。 */
    @Select("""
            SELECT COUNT(*)
            FROM im_user_tag
            WHERE user_id = #{userId}
            """)
    int countByUserId(@Param("userId") Long userId);

    /**
     * 一次取多个用户的绑定行，按人分组后由服务层去字典里拿名字。
     *
     * <p>交友卡池一页就是十几个人，{@code listByUser} 那个单人版逐个调会把一次列表
     * 变成十几条 SQL；排序与单人版保持一致（先 sort 后 id），保证列表与资料卡上的标签同序。
     */
    default List<UserTag> selectByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<UserTag>lambdaQuery()
                .in(UserTag::getUserId, userIds)
                .orderByAsc(UserTag::getUserId)
                .orderByAsc(UserTag::getSort)
                .orderByAsc(UserTag::getId));
    }
}
