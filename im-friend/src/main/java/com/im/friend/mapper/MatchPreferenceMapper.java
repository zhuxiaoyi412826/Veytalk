package com.im.friend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.friend.entity.MatchPreference;
import org.apache.ibatis.annotations.Mapper;

/**
 * 筛选偏好 Mapper。
 *
 * <p>只有「按人取那一行」一种读法：{@code uk_user} 保证一人一行，所以不需要分页也不需要列表
 * （后台那一屏属 f13，届时按 {@code im_admin_*} 那套另开查询）。
 */
@Mapper
public interface MatchPreferenceMapper extends BaseMapper<MatchPreference> {

    /** 本人的偏好行；从没保存过偏好的人返回 {@code null}（<b>不为此插一行</b>，见 Service 注释）。 */
    default MatchPreference selectByUserId(Long userId) {
        return selectOne(Wrappers.<MatchPreference>lambdaQuery()
                .eq(MatchPreference::getUserId, userId));
    }
}
