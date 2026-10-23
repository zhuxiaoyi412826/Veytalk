package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.DashboardVO;
import com.im.admin.service.DashboardService;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台看板。
 *
 * <p>只有一个只读端点：一期没有「改配置」的能力（那些旋钮都在 {@code application.yml} 里），
 * 所以这里不存在写操作，也就没有幂等与审计的负担。
 *
 * <p>二期把权限点从伞权限 {@code system:manage} 细化到 {@code system:dashboard}：看板只读、
 * 不涉及任何处置动作，是权限最低的一屏，因此审核员（moderator）、运营（operator）都该看得到。
 * admin 角色同时持有伞权限与本点，不受影响。
 *
 * <p>{@code refresh=true} 会绕过缓存真跑一遍聚合，因此它带了一个轻量的前提说明：
 * 每次强刷都是若干条 COUNT，页面上不该把它做成自动轮询（前端定时器走默认缓存路径，
 * 只有人手点「刷新」才传这个参数）。
 */
@Tag(name = "16-管理后台", description = "看板：平台数字与举报统计")
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_DASHBOARD)
public class AdminDashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "看板数据",
            description = "分区数字由各业务模块经 AdminMetricsSpi 报上来（模块未装配就没有那个分区，而不是补一个 0）；"
                    + "举报统计来自本模块的表。响应里的 cachedAt/staleSeconds 说明这屏数字截至何时，"
                    + "null 值显示为「—」而不是 0（0 会被读成「今天真的没人注册」）")
    @Parameter(description = "是否绕过缓存重新聚合", name = "refresh")
    @GetMapping
    public Result<DashboardVO> dashboard(@RequestParam(value = "refresh", required = false, defaultValue = "false")
                                         boolean refresh) {
        return Result.ok(dashboardService.dashboard(refresh));
    }
}
