package com.im.friend.convert;

import com.im.common.domain.DatingCardDTO;
import com.im.common.enums.MatchAction;
import com.im.common.enums.MatchSource;
import com.im.friend.dto.vo.MatchActionVO;
import com.im.friend.dto.vo.MatchLikeVO;
import com.im.friend.dto.vo.MatchPairVO;
import com.im.friend.entity.MatchLike;
import com.im.friend.entity.MatchPair;

/**
 * 滑动匹配实体到视图对象的转换。
 *
 * <p>与 {@code FriendConvert} 同一约定：无状态、不注入 SPI，卡片由 Service 批量查好后作为参数传进来。
 * 一次列表渲染因此只需一次 {@code DatingQuerySpi.cards}，而不是逐个人去拼资料、照片与标签。
 */
public final class MatchConvert {

    private MatchConvert() {
    }

    /**
     * 组装一次滑动的返回。
     *
     * @param pair 成对后的配对行；未成对传 {@code null}，此时 pairId / conversationId 都为空
     */
    public static MatchActionVO toActionVO(MatchAction action, MatchPair pair, DatingCardDTO peer, String message) {
        boolean matched = pair != null && pair.isActive();
        return MatchActionVO.builder()
                .action(action.getCode())
                .actionDesc(action.getDesc())
                .matched(matched)
                .pairId(matched ? pair.getId() : null)
                .conversationId(matched ? pair.getConversationId() : null)
                .peer(peer)
                .message(message)
                .build();
    }

    /**
     * 组装「谁喜欢我」列表项。
     *
     * @param row       对方的滑动行（{@code action=1}）
     * @param card      对方卡片，账号已注销时由调用方过滤掉整行，这里不会收到 null
     * @param likedBack 我是否也喜欢过 TA
     * @param pair      我们之间的配对行；没配过传 {@code null}
     */
    public static MatchLikeVO toLikeVO(MatchLike row, DatingCardDTO card, boolean likedBack, MatchPair pair) {
        boolean paired = pair != null && pair.isActive();
        return MatchLikeVO.builder()
                .likeId(row.getId())
                .peerUserId(row.getFromUserId())
                .card(card)
                // 排序与展示都取最近一次动作时间：老喜欢又反悔改回来的行应该重新冒到前面
                .likeTime(row.getUpdateTime() != null ? row.getUpdateTime() : row.getCreateTime())
                .source(row.getSource())
                .sourceDesc(MatchSource.descOf(row.getSource()))
                .likedBack(likedBack)
                .paired(paired)
                .conversationId(paired ? pair.getConversationId() : null)
                .build();
    }

    /**
     * 组装我的配对列表项。
     *
     * @param peerId 由 Service 按「viewer 相对的另一方」算好传入，而不是从卡片反推：
     *               账号已注销时卡片为 null，列表项仍要带着 peerId 才能渲染出一个可点开的占位行
     */
    public static MatchPairVO toPairVO(MatchPair pair, Long peerId, DatingCardDTO card) {
        return MatchPairVO.builder()
                .pairId(pair.getId())
                .peerUserId(peerId)
                .card(card)
                .conversationId(pair.getConversationId())
                .status(pair.getStatus())
                .pairTime(pair.getCreateTime())
                .likeSource(pair.getLikeSource())
                .likeSourceDesc(MatchSource.descOf(pair.getLikeSource()))
                .msgCount(pair.getMsgCount())
                .lastMsgTime(pair.getLastMsgTime())
                .build();
    }

    /** 滑动后的提示文案：只有真成了对才说「配对成功」，否则一句「已喜欢」不能给用户假希望。 */
    public static String actionMessage(MatchAction action, boolean matched) {
        if (action == MatchAction.PASS) {
            return "已跳过";
        }
        return matched ? "配对成功，开始聊天吧" : "已喜欢 TA，等 TA 也喜欢你";
    }
}
