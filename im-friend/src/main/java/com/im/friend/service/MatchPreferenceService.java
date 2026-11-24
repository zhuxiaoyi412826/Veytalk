package com.im.friend.service;

import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.friend.dto.req.PreferenceRequest;
import com.im.friend.dto.vo.PreferenceVO;
import com.im.friend.entity.MatchPreference;

/**
 * 筛选与偏好服务。
 *
 * <p>两类调用方：前端弹层（{@link #get} / {@link #save}），以及卡池与附近的人的组装方
 * （{@link #find} + {@link #applyTo}）。后者拿的是实体而不是 VO——条件要摊进跨模块的查询 DTO，
 * 不该绕一圈先去拼展示文案。
 */
public interface MatchPreferenceService {

    /** 本人偏好；从没保存过也回一组出厂默认值（{@code saved=false}），前端弹层因此不需要处理 null。 */
    PreferenceVO get(Long userId);

    /** 整组替换本人的偏好，返回保存后的结果（越界与自相矛盾的组合会报错而不是夹紧）。 */
    PreferenceVO save(Long userId, PreferenceRequest request);

    /** 原始偏好行；没保存过返回 {@code null}，<b>不为此插行</b>。 */
    MatchPreference find(Long userId);

    /**
     * 把偏好摊进候选池查询条件：<b>只填调用方没有显式给的字段</b>。
     *
     * <p>「只填空着的地方」是为了让「附近的人」那种带显式半径的读法能覆盖偏好里的距离，
     * 而其余条件（年龄、城市、学历…）照旧从偏好走，不需要调用方重复声明一遍。
     *
     * @param preference 可以为 {@code null}（这个人还没保存过偏好），此时什么都不做
     */
    void applyTo(MatchPreference preference, DatingCandidateQueryDTO query);
}
