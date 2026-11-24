package com.im.friend.service;

import com.im.common.domain.MomentCardDTO;
import com.im.friend.dto.req.MomentCommentRequest;
import com.im.friend.dto.req.MomentPublishRequest;
import com.im.friend.dto.req.MomentShareRequest;
import com.im.friend.dto.vo.MomentCommentVO;
import com.im.friend.dto.vo.MomentShareVO;
import com.im.friend.dto.vo.MomentVO;

import java.util.List;

/**
 * 动态 Feed（发布 / 浏览 / 点赞 / 评论 / 删除 / 转聊天）。
 *
 * <p><b>可见性判定只在本接口这一侧发生一次</b>：Feed、个人主页、详情、点赞、评论与转聊天
 * 走的是同一套判据（受众 → 单条 {@code visibility} → 白名单 → 作者的「几天可见」），
 * 收在实现类的同一个私有方法里。分开写就是两套口径，而漏的那一套永远是「多给出去」的那一边。
 *
 * <p>列表一律用<b>游标</b>而不用页码：动态是随时在插入的流，第 2 页在第 1 页看完之后
 * 才去取，中间新发的那几条会把旧内容往下挤，页码分页的表现是「同一条动态看到两次」。
 */
public interface MomentService {

    /**
     * 发布一条动态。
     *
     * <p>正文与附件至少要有一样；附件只认「本人上传且 {@code bizType} 对得上」的文件，
     * 地址由服务端现拼。选「部分人可见」时白名单至少要有一人。
     *
     * @return 发布后的动态（作者视角，因此带 {@code visibilityDesc} 与 {@code status}）
     */
    MomentVO publish(Long userId, MomentPublishRequest request);

    /**
     * 我的动态流：受众 = 我的好友 ∪ 我配对过的人 ∪ 我自己，再减掉我拉黑的人。
     *
     * <p>返回条数少于 {@code size} <b>通常</b>表示刷到底了；但候选还要在内存里过可见范围那三道闸，
     * 所以极端情况下（一整批候选都是别人「仅自己」的动态）也会少给。前端只需把最后一条的
     * {@code momentId} 当下一页游标，拿到空列表就是真的到底了。
     *
     * @param cursorId 上一页最后一条的动态 ID，首页传 {@code null}
     * @param size     期望条数，为空按 10，上限 30
     */
    List<MomentVO> feed(Long userId, Long cursorId, Integer size);

    /**
     * 某个人主页上的动态。
     *
     * <p>看自己时连已下架的一起给（他要能看到「我这条被下架了」，否则只会以为发布失败再发一遍）；
     * 看别人时按对方的「几天可见」设时间下界，并逐条过可见范围。
     * 我拉黑的人返回空列表而不是报错——主页空空如也就是拉黑该有的样子。
     */
    List<MomentVO> userMoments(Long viewerId, Long targetUserId, Long cursorId, Integer size);

    /**
     * 一条动态的详情，带全部评论（两层）。
     *
     * <p>从聊天卡片点进来也走这一条：可见性按<b>点开的人</b>重算，不在受众里、
     * 或已超出作者的「几天可见」都回「这条动态不在了」。
     */
    MomentVO detail(Long viewerId, Long momentId);

    /** 删除自己的动态：连带附件、评论、点赞与白名单一起清（{@code im_file} 一行都不动）。 */
    void delete(Long userId, Long momentId);

    /**
     * 点赞，重复点按幂等处理（不会把计数点上去两次）。
     *
     * @return 点赞后的总数，前端直接拿它刷新红心旁边的数字
     */
    Integer like(Long userId, Long momentId);

    /**
     * 取消点赞。
     *
     * @return 取消后的总数；本来就没赞过时也返回当前总数，不报错
     */
    Integer unlike(Long userId, Long momentId);

    /**
     * 评论或回复。
     *
     * <p>回复一条回复时 {@code parentId} 仍传那条<b>一级</b>评论的 ID（两层封顶），
     * 被回复人由服务端按 {@code parentId} 复核，客户端传错不会串人。
     *
     * @return 新评论（已带上评论人快照，前端可直接插进列表）
     */
    MomentCommentVO comment(Long userId, Long momentId, MomentCommentRequest request);

    /**
     * 删除一条评论：<b>评论作者或动态作者</b>都能删（后者是「我不想在我的动态下看到这句」）。
     *
     * <p>删一级评论会连它下面的回复一起删，并把整棵子树的行数从 {@code comment_count} 里减掉。
     */
    void deleteComment(Long userId, Long commentId);

    /**
     * 动态转聊天（f12）：往一个会话里发一张动态卡片。
     *
     * <p><b>转发不改变可见范围</b>：卡片只带摘要不带图，收到的人点开时服务端按他自己与作者的关系
     * 重算一次，不在受众里就是「这条动态不在了」。这个边界必须落在读侧而不是转发侧——
     * 转发侧拦不住二次转发，也拦不住合并转发。
     *
     * @return 目标会话 ID 与落库后的消息 ID（单聊会话可能是这次才建出来的，前端手上原本没有它的 ID）
     */
    MomentShareVO share(Long userId, Long momentId, MomentShareRequest request);

    /**
     * 取一条动态的卡片快照，供 {@code MomentQuerySpi} 转给 im-message。
     *
     * <p>判定口径与 {@link #detail} 完全一致，所以「能发出去的卡片一定点得开」。
     *
     * @return 看不到（不存在 / 已下架 / 无权）时返回 {@code null}
     */
    MomentCardDTO cardOf(Long momentId, Long viewerId);
}
