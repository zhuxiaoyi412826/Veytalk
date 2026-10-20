package com.im.live.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.live.dto.req.LiveMuteRequest;
import com.im.live.dto.req.LiveWordsRequest;
import com.im.live.dto.vo.LiveMuteVO;
import com.im.live.service.LiveModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 弹幕房管 REST 接口：主播维护自己那份禁言名单与屏蔽词。
 *
 * <p><b>路径里没有 roomId</b>：数据按主播 id 存（原因见 {@link LiveModerationService} 类注释），
 * 于是没在开播时也能配置——「我的屏蔽词」是主播的长期设置，不该只有直播中才改得动。
 * 当前登录者即 anchorId，天然只有本人能读写自己那份，不需要额外的房间归属校验。
 *
 * <p>与 {@code LiveController} 共用 {@code /api/live} 前缀但分文件：控制面（开播/关播/列表）
 * 与治理面（房管）的变更节奏完全不同，挤在一个类里会让「送礼、连麦、房管」的接口互相淹没。
 */
@Tag(name = "10-直播", description = "弹幕房管：禁言名单与主播自定义屏蔽词")
@RestController
@RequestMapping("/api/live/moderation")
@RequiredArgsConstructor
@SaCheckLogin
public class LiveModerationController {

    private final LiveModerationService moderationService;

    @Operation(summary = "禁言名单", description = "我（主播）当前的弹幕禁言名单，按禁言时刻倒序；已过期的不返回")
    @GetMapping("/mutes")
    public Result<List<LiveMuteVO>> mutes() {
        return Result.ok(moderationService.listMutes(SecurityUtil.getUserId()));
    }

    @Operation(summary = "禁言某人",
            description = "分钟数为 0 或负数即永久禁言；带 roomId 时给那人的在线连接即时推一帧提示。名单上限 500 人")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.mute")
    @PostMapping("/mute")
    public Result<Void> mute(@RequestBody @Valid LiveMuteRequest request) {
        moderationService.mute(SecurityUtil.getUserId(), request.getUserId(),
                request.getMinutes(), request.getRoomId());
        return Result.ok();
    }

    @Operation(summary = "解除禁言", description = "幂等：不在名单里也返回成功")
    @DeleteMapping("/mute/{userId}")
    public Result<Void> unmute(@PathVariable("userId") Long userId,
                               @RequestParam(required = false) Long roomId) {
        moderationService.unmute(SecurityUtil.getUserId(), userId, roomId);
        return Result.ok();
    }

    @Operation(summary = "屏蔽词列表",
            description = "我自定义的弹幕屏蔽词（已折叠为小写去空白的形式），命中即拒发；全局敏感词另由服务端词库遮罩")
    @GetMapping("/words")
    public Result<List<String>> words() {
        return Result.ok(moderationService.wordsOf(SecurityUtil.getUserId()));
    }

    @Operation(summary = "整表替换屏蔽词",
            description = "传空数组即清空；单个词最长 20 字、最多 50 个，超出直接报错而不是静默截断")
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "live.words")
    @PutMapping("/words")
    public Result<List<String>> replaceWords(@RequestBody @Valid LiveWordsRequest request) {
        Long anchorId = SecurityUtil.getUserId();
        moderationService.replaceWords(anchorId, request.getWords());
        return Result.ok(moderationService.wordsOf(anchorId));
    }
}
