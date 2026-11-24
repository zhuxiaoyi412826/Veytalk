package com.im.friend.service;

import com.im.friend.dto.vo.NearbyPeerVO;

import java.util.List;

/**
 * 「附近的人」查询服务。
 *
 * <p>与滑动匹配的关系是「同一个候选池的两种读法」：坐标、geohash、采信期这些都在 im-user 的
 * 资料表里，本服务只负责把半径与排除名单传过去、再把回来的 ID 拼成列表项。
 */
public interface NearbyService {

    /**
     * 按距离由近到远取附近的人。
     *
     * @param radiusMeters 半径（米），为空取默认；越界由实现方夹紧
     * @param size         期望条数，为空取默认
     * @return 按距离排序的列表；附近没人时返回空集合
     * @throws com.im.common.exception.BusinessException 查看者自己没有可用定位时（2042）
     */
    List<NearbyPeerVO> nearby(Long userId, Integer radiusMeters, Integer size);
}
