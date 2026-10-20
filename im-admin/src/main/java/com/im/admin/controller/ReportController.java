package com.im.admin.controller;

import com.im.admin.dto.req.ReportSubmitRequest;
import com.im.admin.dto.vo.ReportConfigVO;
import com.im.admin.dto.vo.ReportVO;
import com.im.admin.service.ReportService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.domain.PageQuery;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户侧的举报入口：提交举报、看字典、查自己的进展。
 *
 * <p>与后台接口（{@code /api/admin/**}）分开是权限边界：这三个端点<b>任何登录用户都能调</b>，
 * 所以它绝不能出现在带 {@code @SaCheckPermission} 的那几个类里；
 * 反过来后台端点也不该被前端聊天页面误调——路径前缀就是那道墙，
 * 真正的把关在 service 里（举报人只取自登录态、分页只查自己那部分）。
 *
 * <p>限流按用户维度且刻意比配额本身严一些：配额（默认一天 20 条）是「防刷爆队列」，
 * 这里的 10 次/分钟是「防脚本空转」。两层都要，缺一层都有人能找到缝。
 */
@Tag(name = "15-举报", description = "提交举报、举报字典与我的举报进展")
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "举报字典与开关",
            description = "理由、对象类型、处置动作三份码表由服务端出，前端不复制一份；enabled=false 时入口应整体隐藏")
    @GetMapping("/config")
    public Result<ReportConfigVO> config() {
        return Result.ok(reportService.config());
    }

    @Operation(summary = "提交举报",
            description = "举报人只取登录态，请求体里没有 reporterId；提交即抓证据快照（对象事后被删不影响处置）。"
                    + "同一对象 24 小时内去重、每人每天有条数上限，超限分别回 14003 / 14004")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "report.submit")
    @PostMapping
    public Result<Long> submit(@RequestBody @Valid ReportSubmitRequest request) {
        return Result.ok(reportService.submit(SecurityUtil.getUserId(), request), "举报已提交，等待处理");
    }

    @Operation(summary = "我的举报记录",
            description = "只看自己报出去的那几条，含处理结论；不含处置人是谁")
    @GetMapping("/mine")
    public Result<PageResult<ReportVO>> mine(@Parameter(description = "分页参数") PageQuery query) {
        return Result.ok(reportService.mine(SecurityUtil.getUserId(), query));
    }
}
