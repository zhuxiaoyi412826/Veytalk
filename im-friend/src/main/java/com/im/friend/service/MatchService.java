package com.im.friend.service;

import com.im.common.api.PageResult;
import com.im.common.domain.DatingCardDTO;
import com.im.common.domain.PageQuery;
import com.im.friend.dto.req.MatchActionRequest;
import com.im.friend.dto.vo.MatchActionVO;
import com.im.friend.dto.vo.MatchLikeVO;
import com.im.friend.dto.vo.MatchPairVO;

import java.util.List;

/**
 * 滑动匹配（{@code im_match_like} + {@code im_match_pair}）。
 *
 * <p>归 im-friend：这两张表记的是<b>人与人之间</b>的关系与动作，而「这个人是什么」
 * （资料、照片、标签）归 im-user，跨过来只走 {@code DatingQuerySpi}。
 *
 * <p>配对<b>不自动加好友</b>（用户选定口径）：成对只创建一个私聊会话，好友关系仍然要走
 * 正常的好友申请，否则「滑到喜欢」就等于绕过了好友那一套验证与拉黑规则。
 */
public interface MatchService {

    /**
     * 取一批待滑动的卡片（推荐流）。
     *
     * <p>排除的是「我已经表过态的人」（喜欢过或跳过过）、当前配对中的对象、以及我拉黑的人；
     * 已解除配对的人不会自动回到卡池（滑过这个事实不会被撤销），要重新成对走「喜欢我的」列表。
     *
     * <p>这一栏是<b>推荐流</b>，所以它独享两件事：低质号不进池，以及把本次返回的人记进曝光池
     * （{@code im_match_exposure}）。「附近的人」与「喜欢我的」都不记账：它们只是一个列表，
     * 而一次「看了一眼」就把人的曝光时间推后，会让推荐流自己把自己饿死。
     *
     * @param size 期望张数，越界会被夹到 {@code [1, 30]}
     * @param sort 本次的排序口径（{@code DatingSort} 的 code）；<b>null 与认不出的脏值都退回
     *             偏好里存的那个</b>，而不是退回出厂的 {@code recommend}——用户切一次排序不该
     *             把自己存好的偏好洗掉
     * @return 按候选池顺序，可能少于 {@code size}（人刷完了）；不会为 null
     */
    List<DatingCardDTO> deck(Long userId, Integer size, String sort);

    /**
     * 记一次滑动：喜欢或跳过。
     *
     * <p>同一个方向只有一行（{@code uk_from_to}），重复滑动改的是这一行的动作，
     * 所以「先喜欢后跳过」不会留下两条记录，也不会因为再插一条而报错。
     *
     * <p>双向喜欢时成对：建（或复用）私聊会话、写 {@code im_match_pair}，
     * 系统通知与 WebSocket 推送挂在事务提交之后。
     */
    MatchActionVO act(Long userId, MatchActionRequest request);

    /** 「谁喜欢我」，按对方最近一次改动作的时间倒序。 */
    PageResult<MatchLikeVO> likesReceived(Long userId, PageQuery query);

    /** 我当前配对中的人，按配对时间倒序。 */
    PageResult<MatchPairVO> pairs(Long userId, PageQuery query);

    /**
     * 解除配对：把配对行置为已解除，<b>不删会话、不删聊天记录、不动喜欢流水</b>。
     *
     * <p>会话删除是会话侧的独立动作，不该被「解除配对」顺手带走；而留着喜欢流水，
     * 两人日后重新喜欢时会复用同一条配对记录（置回配对中），历史成对时间才对得上。
     */
    void unmatch(Long userId, Long pairId);
}
