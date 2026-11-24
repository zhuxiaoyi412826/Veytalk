package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.vo.VisitorVO;
import com.im.friend.service.ProfileVisitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「谁看过我」接口，路径同样挂在 {@code /api/dating/**} 下，自动落进 Knife4j 的「20-交友」分组。
 *
 * <p>只要登录、不要权限码：这一屏永远只回「谁看了<em>我</em>」，查不到别人的足迹，
 * 与「谁喜欢我」是同一类越权面（都没有）。
 *
 * <p>没有分页参数是有意为之：返回的是一个 30 天窗口内的短列表（上限 50 位），
 * 而不是能翻到第 N 页的表格。真要分页就得按<b>人</b>分页，而一句 GROUP BY 之后
 * 的 total 是行数不是人数，需要额外的子查询计数——为一个最多 50 项的列表不值。
 * 后续的会员权益（「解锁完整访客列表」）落地时才会需要分页，那时再加参数。
 */
@Tag(name = "20-交友", description = "访客记录：谁看过我的资料卡")
@Validated
@RestController
@RequestMapping("/api/dating/visits")
@RequiredArgsConstructor
@SaCheckLogin
public class VisitController {

    private final ProfileVisitService profileVisitService;

    @Operation(summary = "谁看过我",
            description = "近 30 天来看过我的人，按最近一次来访时间倒序、一人一条；"
                    + "同一天反复打开只算一次来访但会累加 visitTimes；开了隐身的人来看我不会留下记录")
    @RateLimit(count = 60, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.visits")
    @GetMapping
    public Result<List<VisitorVO>> visits() {
        return Result.ok(profileVisitService.recentVisitors(SecurityUtil.getUserId()));
    }
}
