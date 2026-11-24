package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.req.PreferenceRequest;
import com.im.friend.dto.vo.PreferenceVO;
import com.im.friend.service.MatchPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「筛选与偏好」接口，路径 {@code /api/dating/preference} 同样落在 Knife4j 的「20-交友」分组里。
 *
 * <p>写侧用 {@code user:profile:update} 而不是 {@code friend:apply}：偏好的作用对象是<b>我自己的视图</b>
 * （和改资料同类），而 {@code friend:apply} 的含义是「能向陌生人发起接触」。拿后者来 gate 设置项，
 * 会让一个只允许浏览的账号连「只看同城」都设不了，而这两件事本来互不相干。
 * V1.0.7 没有为用户侧预留新权限位，这两个已有码里只有前者对得上语义。
 *
 * <p>限流 20 次 / 60s：这是一张弹层里的保存按钮，正常用户一分钟点不到五次；
 * 真正的读端点（卡池、附近的人）不查这个 key，所以不会因为偏好被限流而连带卡死刷人。
 */
@Tag(name = "20-交友", description = "筛选与偏好：性别 / 年龄 / 距离 / 城市 / 身高 / 学历 / 婚恋 + 在线优先与排序口径")
@Validated
@RestController
@RequestMapping("/api/dating/preference")
@RequiredArgsConstructor
@SaCheckLogin
public class PreferenceController {

    private final MatchPreferenceService matchPreferenceService;

    @Operation(summary = "我的筛选偏好",
            description = "从没保存过也回一组出厂默认值并带 saved=false，前端不必处理 null")
    @GetMapping
    public Result<PreferenceVO> get() {
        return Result.ok(matchPreferenceService.get(SecurityUtil.getUserId()));
    }

    @Operation(summary = "保存筛选偏好",
            description = "**整组替换**（不是「null 字段不修改」）：年龄 / 身高区间反了、城市只有一个字都会报错而不是夹紧；"
                    + "保存后下一次取卡池与附近的人立即按新条件生效")
    @SaCheckPermission(ImConstants.PERM_USER_UPDATE)
    @RateLimit(count = 20, seconds = 60, dimension = RateLimit.Dimension.USER, key = "dating.preference.save")
    @PutMapping
    public Result<PreferenceVO> save(@RequestBody @Valid PreferenceRequest request) {
        return Result.ok(matchPreferenceService.save(SecurityUtil.getUserId(), request), "筛选条件已保存");
    }
}
