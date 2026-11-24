package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.req.MomentCommentRequest;
import com.im.friend.dto.req.MomentPublishRequest;
import com.im.friend.dto.req.MomentShareRequest;
import com.im.friend.dto.vo.MomentCommentVO;
import com.im.friend.dto.vo.MomentShareVO;
import com.im.friend.dto.vo.MomentVO;
import com.im.friend.service.MomentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动态 Feed 接口，路径挂在 {@code /api/dating/moments/**} 下，因此自动落进 Knife4j 的「20-交友」分组
 * （分组按路径前缀切，见 {@code Knife4jConfig}，新增子路径不需要改配置）。
 *
 * <p><b>权限点按「动的是谁的内容」分三档</b>：
 * <ul>
 *   <li>发布 / 删除 / 删评论：只要登录。动的是自己的内容，服务层已经按作者身份判过一道，
 *       再挂权限点只是多一个会被忘掉的开关（与 im-live 四屏的 UGC 口径一致，那里也一律只 {@code @SaCheckLogin}）；</li>
 *   <li>点赞 / 评论：{@code friend:apply}。<b>对别人的内容做出一个会被对方看到的动作</b>，
 *       语义上就是一次接触，与好友申请、滑动喜欢是同一种能力（同 {@code MatchController} 的口径）；</li>
 *   <li>转聊天：{@code message:send}。它产出的是一条消息，一个被收回发消息权限的账号
 *       不该因为换了一个入口就能把动态塞进别人的会话。</li>
 * </ul>
 * 刻意<b>不为动态新开权限点</b>：那要再补一条 {@code im_permission}，
 * 让已经发出去的账号包多一次迁移，而 V1.0.7 也没为用户侧预留新权限位。
 *
 * <p>{@code GET /feed} 与 {@code GET /{momentId}} 不歧义：Spring 匹配时字面量路径优先于模板，
 * 不会被当成一个 momentId 为 "feed" 的详情请求。
 *
 * <p>限流按动作频率定：发布 10 次/分钟（每次都落一行 + 最多九行附件），
 * 点赞与评论 60 次/分钟（人手连点的上限），读侧 60 次/分钟。
 */
@Tag(name = "20-交友", description = "动态 Feed：发布、动态流、个人主页、详情、点赞、评论、删除与转聊天")
@Validated
@RestController
@RequestMapping("/api/dating/moments")
@RequiredArgsConstructor
@SaCheckLogin
public class MomentController {

    private final MomentService momentService;

    @Operation(summary = "发布动态",
            description = "正文与附件至少要有一样；附件传已上传好的 fileId（bizType 必须是 moment_image / moment_video），"
                    + "地址由服务端现拼；最多 9 张图或 1 个视频，两者不混排；"
                    + "可见范围 1 公开（好友与配对对象可见） 2 仅好友 3 部分人可见 4 仅自己，选 3 时 visibleUserIds 至少要有一人")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.publish")
    @PostMapping
    public Result<MomentVO> publish(@RequestBody @Valid MomentPublishRequest request) {
        return Result.ok(momentService.publish(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "我的动态流",
            description = "受众 = 我的好友 ∪ 我配对过的人 ∪ 我自己，再减掉我拉黑的人；按发布时间倒序游标翻页，"
                    + "把返回的最后一条 momentId 当下一页的 cursorId，拿到空列表就是到底了；"
                    + "「公开」不是全站公开，陌生人的动态不会出现在这一栏")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.feed")
    @GetMapping("/feed")
    public Result<List<MomentVO>> feed(
            @Parameter(description = "上一页最后一条动态 ID，首页不传")
            @RequestParam(required = false) Long cursorId,
            @Parameter(description = "期望条数 1-30，默认 10")
            @RequestParam(required = false) @Min(value = 1, message = "size 最小为 1")
            @Max(value = 30, message = "size 最大为 30") Integer size) {
        return Result.ok(momentService.feed(SecurityUtil.getUserId(), cursorId, size));
    }

    @Operation(summary = "某个人主页上的动态",
            description = "看自己时连已下架的一起给（status/statusDesc 只对本人下发）；"
                    + "看别人时按对方的「几天可见」设时间下界，并逐条过可见范围，"
                    + "陌生人这里恒为空列表；我拉黑的人也是空列表而不是报错")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.user")
    @GetMapping("/user/{userId}")
    public Result<List<MomentVO>> userMoments(
            @Parameter(description = "目标用户 ID") @PathVariable Long userId,
            @Parameter(description = "上一页最后一条动态 ID，首页不传")
            @RequestParam(required = false) Long cursorId,
            @Parameter(description = "期望条数 1-30，默认 10")
            @RequestParam(required = false) @Min(value = 1, message = "size 最小为 1")
            @Max(value = 30, message = "size 最大为 30") Integer size) {
        return Result.ok(momentService.userMoments(SecurityUtil.getUserId(), userId, cursorId, size));
    }

    @Operation(summary = "动态详情",
            description = "带全部评论（两层：一级评论在外，回复挂在 replies 里）；"
                    + "从聊天卡片点进来也走这一条，可见性按点开的人重算，"
                    + "不在受众里或已超出作者的「几天可见」都回 2050「这条动态不在了」")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.detail")
    @GetMapping("/{momentId}")
    public Result<MomentVO> detail(@Parameter(description = "动态 ID") @PathVariable Long momentId) {
        return Result.ok(momentService.detail(SecurityUtil.getUserId(), momentId));
    }

    @Operation(summary = "删除自己的动态",
            description = "连带附件、评论、点赞与白名单一起清；im_file 一行都不动（秒传时那份字节可能正被别处引用）")
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.delete")
    @DeleteMapping("/{momentId}")
    public Result<Void> delete(@Parameter(description = "动态 ID") @PathVariable Long momentId) {
        momentService.delete(SecurityUtil.getUserId(), momentId);
        return Result.ok(null, "已删除");
    }

    @Operation(summary = "点赞", description = "重复点按幂等处理，不会把计数点上去两次；返回点赞后的总数")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.like")
    @PostMapping("/{momentId}/like")
    public Result<Integer> like(@Parameter(description = "动态 ID") @PathVariable Long momentId) {
        return Result.ok(momentService.like(SecurityUtil.getUserId(), momentId));
    }

    @Operation(summary = "取消点赞", description = "本来就没赞过也返回当前总数，不报错")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.unlike")
    @DeleteMapping("/{momentId}/like")
    public Result<Integer> unlike(@Parameter(description = "动态 ID") @PathVariable Long momentId) {
        return Result.ok(momentService.unlike(SecurityUtil.getUserId(), momentId));
    }

    @Operation(summary = "评论或回复",
            description = "回复一条回复时 parentId 仍传那条一级评论的 ID（两层封顶），"
                    + "replyUserId 由服务端按 parentId 复核，传错不会串人；返回新评论，前端可直接插进列表")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.comment")
    @PostMapping("/{momentId}/comments")
    public Result<MomentCommentVO> comment(@Parameter(description = "动态 ID") @PathVariable Long momentId,
                                           @RequestBody @Valid MomentCommentRequest request) {
        return Result.ok(momentService.comment(SecurityUtil.getUserId(), momentId, request));
    }

    @Operation(summary = "删除评论",
            description = "评论作者或动态作者都能删；删一级评论会连它下面的回复一起删，"
                    + "并把整棵子树的行数从 commentCount 里减掉")
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.comment.delete")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@Parameter(description = "评论 ID") @PathVariable Long commentId) {
        momentService.deleteComment(SecurityUtil.getUserId(), commentId);
        return Result.ok(null, "已删除");
    }

    @Operation(summary = "动态转聊天",
            description = "往一个会话里发一张动态卡片（msgType=15），目标定位 conversationId 优先于 toUserId；"
                    + "message 是可选附言。★ 转发不改变可见范围：卡片只带摘要不带图，"
                    + "收到的人点开时按他自己与作者的关系重算，不在受众里就是「这条动态不在了」")
    @SaCheckPermission(ImConstants.PERM_MESSAGE_SEND)
    @RateLimit(count = 30, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.moment.share")
    @PostMapping("/{momentId}/share")
    public Result<MomentShareVO> share(@Parameter(description = "动态 ID") @PathVariable Long momentId,
                                       @RequestBody @Valid MomentShareRequest request) {
        return Result.ok(momentService.share(SecurityUtil.getUserId(), momentId, request));
    }
}
