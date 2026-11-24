package com.im.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.im.user.entity.UserPhoto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 照片墙 Mapper。
 *
 * <p>批量计数单独给一个 default 方法：完资料度、推荐流卡片都要在「一批人」上拿通过张数，
 * 逐个 count 会把一次列表查询变成 N+1 条 SQL。
 */
@Mapper
public interface UserPhotoMapper extends BaseMapper<UserPhoto> {

    /** 单个用户的通过张数，完资料度按它给满 30 分。 */
    @Select("""
            SELECT COUNT(*)
            FROM im_user_photo
            WHERE user_id = #{userId}
              AND audit_status = 1
              AND deleted = 0
            """)
    int countApproved(@Param("userId") Long userId);

    /**
     * 一次取多个用户<b>已审核通过</b>的照片行，按人分组后在服务层挑封面。
     *
     * <p>给卡池与列表页用：{@code cover(userId)} 是单人版，逐个调就把一页卡片变成 size 条 SQL。
     * 这里刻意只过滤已通过的行——「哪一张能被看到」的判定不收在调用方，
     * 否则每个列表功能都会自己写一份，迟早有一处把待审的图发出去。
     */
    default List<UserPhoto> selectApprovedByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<UserPhoto>lambdaQuery()
                .in(UserPhoto::getUserId, userIds)
                .eq(UserPhoto::getAuditStatus, UserPhoto.AUDIT_PASSED)
                .orderByAsc(UserPhoto::getUserId)
                .orderByAsc(UserPhoto::getSort)
                .orderByAsc(UserPhoto::getId));
    }

    /**
     * 一次 GROUP BY 统计多个用户的通过张数，供列表页渲染「TA 有几张公开照片」。
     *
     * @return key 为 userId，<b>没有通过照片的用户不会出现在结果里</b>，调用方按 0 处理
     */
    default Map<Long, Integer> countApprovedByUserIds(Collection<Long> userIds) {
        return countByAuditStatus(userIds, UserPhoto.AUDIT_PASSED);
    }

    /**
     * 按<b>任意一档</b>审核状态一次 GROUP BY 统计多个用户的张数。
     *
     * <p>从 {@link #countApprovedByUserIds} 抽出而不是再写一份：后台照片屏要同时看
     * 「已通过几张」与「待审几张」，两个方法体只差一个常量却会各自漂移
     * （最典型的坏法是新加一档状态时只改了一个）。
     *
     * @return key 为 userId，<b>该档一张都没有的用户不出现在结果里</b>，调用方按 0 处理
     */
    default Map<Long, Integer> countByAuditStatus(Collection<Long> userIds, int auditStatus) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = selectMaps(Wrappers.<UserPhoto>query()
                .select("user_id", "COUNT(*) AS cnt")
                .in("user_id", userIds)
                .eq("audit_status", auditStatus)
                .groupBy("user_id"));
        Map<Long, Integer> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object userId = row.get("user_id");
            Object count = row.get("cnt");
            if (userId instanceof Number id && count instanceof Number cnt) {
                result.put(id.longValue(), cnt.intValue());
            }
        }
        return result;
    }
}
