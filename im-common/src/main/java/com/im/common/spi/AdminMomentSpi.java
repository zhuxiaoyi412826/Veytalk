package com.im.common.spi;

import com.im.common.api.PageResult;
import com.im.common.domain.AdminCommentQueryDTO;
import com.im.common.domain.AdminMomentCommentDTO;
import com.im.common.domain.AdminMomentDTO;
import com.im.common.domain.AdminMomentQueryDTO;

/**
 * 后台动态管理契约，由 im-friend 实现（{@code MomentAdminSpiImpl}）。
 *
 * <p>星型架构：{@code im_moment} 及其四张附属表都住在 im-friend，im-admin 只能拿契约要数据。
 *
 * <p>与用户端 {@code MomentService} 的边界（同 {@link AdminVideoSpi} 的理由）：那边每一处读都带
 * 「受众视角」的判定（可见范围、白名单、三天可见、是否本人），后台要的是<b>无视角的全状态行</b>——
 * 「已下架的那条」「仅自己可见的那条」在用户端服务里根本查不出来。所以实现类直接对 mapper 说话，
 * 不复用用户端方法的语义，避免把「跳过可见性判定」这种分支塞进共用路径。
 *
 * <p>删除的连带范围与用户端 {@code MomentService#delete} 严格一致（媒体 / 评论 / 点赞 / 白名单四张
 * 附属表一起清），否则后台删一条动态会留下一堆指向空气的评论行，而它们是能被别的查询读出来的。
 */
public interface AdminMomentSpi {

    /**
     * 翻页查动态，<b>含已下架</b>（status=2）；固定按 ID 倒序（等价发布时间倒序）。
     *
     * <p>已随动态逻辑删的行由 MyBatis-Plus 的全局条件自动挡掉，后台这里看不到也查不到——
     * 删除是不可逆处置，恢复本来就不该是后台按钮。
     */
    PageResult<AdminMomentDTO> pageMoments(AdminMomentQueryDTO query);

    /**
     * 取一条动态的后台详情，<b>不拒已下架的行</b>（运营要能打开刚下架的那一条核对）。
     *
     * <p>不存在或已逻辑删时抛业务异常——它和「你没权限看」不是两件事，后台不需要
     * 用户端那句「这条动态不在了」的模糊回答，那是防枚举用的，防自己人只会添乱。
     */
    AdminMomentDTO detail(Long momentId);

    /**
     * 下架 / 恢复一条动态（改 {@code im_moment.status}：1 正常 2 已下架，零 DDL）。
     *
     * <p>下架后作者本人在自己的主页仍能看到它（用户端口径：作者本人 {@code includeOffline=true}），
     * 这样他知道被下架了而不是以为发布失败去重发一遍。
     *
     * <p>幂等：状态已一致时直接回当前行。记录不存在抛业务异常。
     *
     * @param reason 事由，只进服务端审计日志（这一列不外显给用户，避免把处置理由变成可争论的公开信息）
     */
    AdminMomentDTO setStatus(Long momentId, boolean online, Long operatorId, String reason);

    /**
     * 以管理员身份删除一条动态：逻辑删主行，并级联清媒体 / 评论 / 点赞 / 白名单四张附属表。
     *
     * <p>点赞与白名单是物理删（这两张表没有 {@code deleted} 列），媒体与评论是逻辑删——
     * 与用户端本人删除完全同一落点，两套删法会留下不同的残留行。幂等。
     */
    void delete(Long momentId, Long operatorId);

    /**
     * 跨动态翻页查评论，<b>含已下架</b>；按 ID 倒序。
     *
     * <p>回复行与一级评论平铺，不组树（见 {@link AdminMomentCommentDTO} 的类注释）。
     */
    PageResult<AdminMomentCommentDTO> pageComments(AdminCommentQueryDTO query);

    /**
     * 下架 / 恢复一条评论。
     *
     * <p>下架一级评论<b>连带它当前的回复</b>（与删除同一口径）：用户端返两层结构时
     * 是按「一级 + 它的子行」组装的，只藏父行会留下一批找不到父的回复——它们既不显示
     * 却仍被计在 {@code comment_count} 里，于是列表永远显示「3 条评论」而屏上只有 2 条。
     *
     * <p>恢复只恢复被点的那一条，不连带子树：子行处于下架态往往是它自己被单独处置过，
     * 连带恢复等于把别人的一次处置抹掉。两个方向都要同步回写 {@code comment_count}，
     * 增减的量一律取「本次真正改动了状态的行数」而不是拍一个 1。
     */
    AdminMomentCommentDTO setCommentStatus(Long commentId, boolean online, Long operatorId, String reason);

    /**
     * 以管理员身份删除一条评论：一级评论连它的回复一起删，并把 {@code im_moment.comment_count}
     * 回减退掉的<b>整棵子树行数</b>（只减 1 会让这个数字永远虚高，与用户端同一口径）。幂等。
     */
    void deleteComment(Long commentId, Long operatorId);
}
