package com.im.friend.service;

import com.im.common.enums.ProfileVisitType;
import com.im.friend.dto.vo.VisitorVO;

import java.util.List;

/**
 * 访客记录「谁看过我」。
 *
 * <p>写入由 im-user 通过 {@code ProfileVisitSpi} 打进来（读资料卡这个入口在那边），
 * 读取在本模块完成：访客是一个关系侧的事实，不该为了查一次足迹把表搬到 im-user。
 */
public interface ProfileVisitService {

    /**
     * 记一次「visitor 看了 visited」。
     *
     * <p>一天一人一行，重复调用只累加次数；<b>本方法不判断该不该记</b>（隐身位由调用方看），
     * 也不吞异常——是否容忍失败由调用方决定（见 {@code ProfileVisitSpi#record} 的契约）。
     *
     * @param type 为 null 时按 {@link ProfileVisitType#PROFILE_CARD} 落，与建表默认值一致
     */
    void record(Long visitorId, Long visitedId, ProfileVisitType type);

    /**
     * 最近看过我的人，按最近一次来访时间倒序。
     *
     * @return 一屏短列表（上限 {@code ProfileVisitMapper.VISITOR_MAX} 位），没人来看过时返回空集合
     */
    List<VisitorVO> recentVisitors(Long userId);
}
