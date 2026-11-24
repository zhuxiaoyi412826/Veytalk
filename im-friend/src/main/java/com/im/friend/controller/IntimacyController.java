package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.vo.IntimacyVO;
import com.im.friend.service.IntimacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 亲密度与火花接口，路径挂在 {@code /api/dating/**} 下，自动落进 Knife4j 的「20-交友」分组。
 *
 * <p>只要登录、不要权限码：永远只回「<em>我</em>和这个人」的一对数据，查不到任何别人的亲密度，
 * 与「谁看过我」是同一类越权面（都没有）。
 *
 * <p>{@code userId} 是必填的：这个端点没有「我的全部亲密度」这种形态——
 * 一张几百行的排行榜既不是一屏能看完的东西，也会把「谁跟谁聊得最多」变成可爬的社交图谱。
 * 真要做一个「火花榜」，那是一个独立的、带分页与排序口径的新端点。
 */
@Tag(name = "20-交友", description = "亲密度与火花：连续聊天天数、互动值、共同好友与共同群")
@Validated
@RestController
@RequestMapping("/api/dating/intimacy")
@RequiredArgsConstructor
@SaCheckLogin
public class IntimacyController {

    private final IntimacyService intimacyService;

    @Operation(summary = "我与某人的亲密度",
            description = "返回分值、档位与档位内进度、当前与历史最高连续天数、火花是否还亮着、"
                    + "累计消息数与互动数、共同好友数与共同群数；从没来往过返回全 0 而不是 null")
    @RateLimit(count = 120, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.intimacy")
    @GetMapping
    public Result<IntimacyVO> intimacy(@RequestParam Long userId) {
        return Result.ok(intimacyService.view(SecurityUtil.getUserId(), userId));
    }
}
