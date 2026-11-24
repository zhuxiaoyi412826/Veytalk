package com.im.friend.service.impl;

import com.im.common.api.ResultCode;
import com.im.common.domain.DatingCandidateQueryDTO;
import com.im.common.domain.DatingCardDTO;
import com.im.common.exception.BusinessException;
import com.im.common.spi.DatingQuerySpi;
import com.im.friend.dto.vo.NearbyPeerVO;
import com.im.friend.entity.MatchPair;
import com.im.friend.entity.MatchPreference;
import com.im.friend.mapper.FriendMapper;
import com.im.friend.mapper.MatchLikeMapper;
import com.im.friend.mapper.MatchPairMapper;
import com.im.friend.service.MatchPreferenceService;
import com.im.friend.service.NearbyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「附近的人」实现。
 *
 * <p>坐标、geohash、采信期、距离模糊化全在 im-user 那一侧（{@link DatingQuerySpi}），
 * 本类只做三件事：判断查看者能不能按距离找人、把排除名单传过去、把回来的 ID 拼成列表项。
 * 这里<em>算不出也拿不到</em>米数，所以不可能顺手把精确距离写进返回值。
 *
 * <p>与卡池（{@code MatchServiceImpl#deck}）的差别是有意为之的：
 * <ul>
 *   <li>不排除「已划过」的人——附近的人是一个 browse 视图，划走过不代表 TA 不在你身边，
 *       而且这一栏带着「已喜欢 / 去聊天」的状态，正需要把划走过的人照出来；
 *   <li>不排除「已配对」的人——配对之后仍然会反复用到这一屏找 TA，
 *       所以 {@code paired} 与 {@code conversationId} 是这一栏的主字段而不是异常分支；
 *   <li>只排除「我拉黑的人」——拉黑是「别再出现在我面前」，对任何入口都成立。
 * </ul>
 *
 * <p>偏好（{@code im_match_preference}）在这一栏同样生效：年龄 / 城市 / 性别那几条是「我想认识谁」，
 * 与从哪里找到 TA 无关。只有两处例外：半径以请求参数为准（请求里带了就是显式覆盖了偏好的
 * {@code distance_km}），而偏好的 {@code sortBy} 与 {@code onlineFirst} 不生效——距离与在线态两个序
 * 互相拆台，收口在 {@code DatingQuerySpiImpl#nearbyIds}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NearbyServiceImpl implements NearbyService {

    /** 默认半径 3 km：一座城里稠密区够用，又不至于把整个市辖区圈进来退化成同城列表 */
    private static final int DEFAULT_RADIUS = 3000;

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final DatingQuerySpi datingQuerySpi;
    private final MatchLikeMapper matchLikeMapper;
    private final MatchPairMapper matchPairMapper;
    private final FriendMapper friendMapper;
    private final MatchPreferenceService matchPreferenceService;

    @Override
    public List<NearbyPeerVO> nearby(Long userId, Integer radiusMeters, Integer size) {
        // 先看查看者自己的定位，再查池子：反过来的话，一个没定位的用户每次翻附近的人
        // 都会白扫一遍 im_user_profile 才收到 2042
        BusinessException.throwIf(!datingQuerySpi.locationReady(userId), ResultCode.DATING_LOCATION_REQUIRED);
        int limit = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(size, MAX_SIZE));
        MatchPreference preference = matchPreferenceService.find(userId);
        DatingCandidateQueryDTO query = DatingCandidateQueryDTO.builder()
                .viewerId(userId)
                .excludeIds(friendMapper.selectBlockedFriendIds(userId))
                .nearbyRadiusMeters(radiusOf(radiusMeters, preference))
                .limit(limit)
                .build();
        // applyTo 只填没显式给过的字段，所以上面的半径不会被偏好里的 distance_km 抢掉；
        // 而调用方没提的那些条件（年龄/城市/性别…）才是从偏好来的
        matchPreferenceService.applyTo(preference, query);
        List<Long> ids = datingQuerySpi.candidateIds(query);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, DatingCardDTO> cards = datingQuerySpi.cards(ids, userId);
        Set<Long> liked = new HashSet<>(matchLikeMapper.selectLikedTargets(userId, ids));
        Map<Long, MatchPair> pairs = activePairMap(userId);

        List<NearbyPeerVO> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            DatingCardDTO card = cards.get(id);
            if (card == null) {
                // 候选池已按账号状态筛过，这里为 null 只剩「批量读与拼装之间被删号」这一种可能；
                // 少一条比渲染一张空位子的卡更可信
                continue;
            }
            MatchPair pair = pairs.get(id);
            items.add(NearbyPeerVO.builder()
                    .peerUserId(id)
                    .card(card)
                    .liked(liked.contains(id))
                    .paired(pair != null)
                    .conversationId(pair == null ? null : pair.getConversationId())
                    .build());
        }
        return items;
    }

    /**
     * 半径的三级取值：请求参数 &gt; 偏好里的 {@code distance_km} &gt; 出厂默认。
     *
     * <p>偏好里存的是公里且「不限」为 0，所以这里而不是 applyTo 里做单位换算与兜底：
     * 半径的默认值属于「附近的人」这一个入口，不是全局筛选条件。
     */
    private static int radiusOf(Integer radiusMeters, MatchPreference preference) {
        if (radiusMeters != null) {
            return radiusMeters;
        }
        return preference == null ? DEFAULT_RADIUS : preference.radiusMetersOr(DEFAULT_RADIUS);
    }

    /** peer → 配对中关系；已解除的不进表，解除不等于拉黑。 */
    private Map<Long, MatchPair> activePairMap(Long userId) {
        Map<Long, MatchPair> map = new HashMap<>();
        for (MatchPair pair : matchPairMapper.selectActiveByUser(userId)) {
            Long peer = pair.peerId(userId);
            if (peer != null) {
                map.put(peer, pair);
            }
        }
        return map;
    }
}
