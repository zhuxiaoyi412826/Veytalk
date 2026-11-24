package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.DatingCardDTO;
import com.im.common.domain.PageQuery;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.req.MatchActionRequest;
import com.im.friend.dto.vo.MatchActionVO;
import com.im.friend.dto.vo.MatchLikeVO;
import com.im.friend.dto.vo.MatchPairVO;
import com.im.friend.service.MatchService;
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
 * 滑动匹配接口，路径挂在 {@code /api/dating/match/**} 下，因此自动落进 Knife4j 的「20-交友」分组
 * （分组按路径前缀切，见 {@code Knife4jConfig}，新增子路径不需要改配置）。
 *
 * <p>写侧四个动作共用 {@code friend:apply} 而不申请新权限码：滑动「喜欢」的语义就是
 * <b>向一个陌生人发起接触</b>，与好友申请是同一种能力；V1.0.7 也没为用户侧预留新权限位，
 * 为此再补一条 {@code im_permission} 会让已经发出去的账号包多一次迁移。
 *
 * <p>三个读端点只要登录：卡池、喜欢我的、我的配对都是「只看得到与自己相关的那份」，
 * 没有越权面。限流按动作频率定：滑动是高速交互，120 次/分钟对应人手滑到底的速度上限，
 * 而解除配对是一个几乎不会连续做第二次的动作，20 次/分钟绰绰有余。
 */
@Tag(name = "20-交友", description = "滑动匹配：卡池、喜欢/跳过、谁喜欢我、我的配对与解除配对")
@Validated
@RestController
@RequestMapping("/api/dating/match")
@RequiredArgsConstructor
@SaCheckLogin
public class MatchController {

    private final MatchService matchService;

    @Operation(summary = "取待滑动的卡池（推荐流）",
            description = "已划过（喜欢过或跳过过）、当前配对中、被我拉黑的人都不出现，低质号也不进这一栏；"
                    + "喜欢过我的人置顶（最多占一半）、七天以内推过的人垫后，卡片上的 matchReason 说明为什么推这张卡；"
                    + "返回少于 size 即刷到底了。这一栏会把返回的人记进曝光池，所以刷得越多新面孔越多")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.match.deck")
    @GetMapping("/deck")
    public Result<List<DatingCardDTO>> deck(
            @Parameter(description = "期望张数 1-30，默认 10")
            @RequestParam(required = false) @Min(value = 1, message = "size 最小为 1")
            @Max(value = 30, message = "size 最大为 30") Integer size,
            @Parameter(description = "本次的排序口径：recommend / active / hot / new（取值见字典接口的 sorts）；"
                    + "不传或认不出的值都按偏好里存的那个，不会把偏好洗掉")
            @RequestParam(required = false) String sort) {
        return Result.ok(matchService.deck(SecurityUtil.getUserId(), size, sort));
    }

    @Operation(summary = "滑动：喜欢或跳过",
            description = "一个方向只记一行（重复滑动改动作），双向喜欢即成对并创建私聊会话，返回体直接带 pairId 与 conversationId；"
                    + "配对不会自动加好友")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @RateLimit(count = 120, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.match.action")
    @PostMapping("/action")
    public Result<MatchActionVO> act(@RequestBody @Valid MatchActionRequest request) {
        return Result.ok(matchService.act(SecurityUtil.getUserId(), request));
    }

    @Operation(summary = "谁喜欢我",
            description = "只含 action=1 的行，按对方最近一次改动作的时间倒序；likedBack 为 true 表示你也喜欢过 TA")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.match.likes")
    @GetMapping("/likes")
    public Result<PageResult<MatchLikeVO>> likes(PageQuery query) {
        return Result.ok(matchService.likesReceived(SecurityUtil.getUserId(), query));
    }

    @Operation(summary = "我的配对", description = "只含配对中的关系，按配对时间倒序；conversationId 可直接跳进聊天")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.match.pairs")
    @GetMapping("/pairs")
    public Result<PageResult<MatchPairVO>> pairs(PageQuery query) {
        return Result.ok(matchService.pairs(SecurityUtil.getUserId(), query));
    }

    @Operation(summary = "解除配对",
            description = "只把配对行置为已解除：会话与聊天记录都不动，喜欢流水也留着，重新喜欢会复用同一条配对记录")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.match.unmatch")
    @DeleteMapping("/pairs/{pairId}")
    public Result<Void> unmatch(@Parameter(description = "配对 ID") @PathVariable Long pairId) {
        matchService.unmatch(SecurityUtil.getUserId(), pairId);
        return Result.ok(null, "已解除配对");
    }
}
