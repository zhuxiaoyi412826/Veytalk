package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.ReportAdminQuery;
import com.im.admin.dto.req.ReportHandleRequest;
import com.im.admin.dto.req.ReportRejectRequest;
import com.im.admin.dto.vo.AdminReportVO;
import com.im.admin.service.DashboardService;
import com.im.admin.service.ReportService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.FileDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 后台的举报工单队列。
 *
 * <p>整个类要求 {@code system:report:handle} 权限点（二期从伞权限 {@code system:manage} 细化而来）：
 * 处置举报是唯一会真去动被举报对象（封号/撤消息/关房间）的动作，与「管人」「配角色」分开授权，
 * 于是只给「看板 + 处置」的审核员（moderator）能工单处置却改不了账号角色。
 * admin 角色同时持有伞权限 {@code system:manage} 与本点，向后兼容、不受影响。
 * <b>权限判定不依赖路径前缀</b>：
 * {@code /api/admin/**} 只是让人一眼看得出这是后台接口，真正的门是这道注解——
 * 万一以后有人加了个 {@code /api/admin/xxx} 却忘了写注解，全局拦截器至少还保证他是登录用户，
 * 不会是匿名者直接看到工单。
 *
 * <p>三个改状态的动作分成受理、驳回、处置三个端点而不是一个 {@code PUT /status}：
 * 三者的校验规则完全不同（受理只改状态、驳回必须给理由、处置要真去动对象），
 * 合成一个接口就得在 body 里塞一个「意图」字段，然后写一堆 if 去猜调用方想干什么。
 */
@Tag(name = "16-管理后台", description = "举报工单队列：查看、受理、驳回与处置")
@RestController
@RequestMapping("/api/admin/report")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_REPORT_HANDLE)
public class AdminReportController {

    private final ReportService reportService;
    private final DashboardService dashboardService;

    @Operation(summary = "工单队列",
            description = "按状态/对象类型/理由码筛选，关键词为纯数字时按对象 ID、被举报人 ID、举报人 ID 精确匹配，"
                    + "否则在补充描述上做模糊匹配；每行带回被举报人的账号状态与未处置工单数")
    @GetMapping("/page")
    public Result<PageResult<AdminReportVO>> page(ReportAdminQuery query) {
        return Result.ok(reportService.adminPage(query));
    }

    @Operation(summary = "工单详情", description = "含完整证据快照与本行可执行的处置动作")
    @GetMapping("/{id}")
    public Result<AdminReportVO> detail(@Parameter(description = "工单 ID") @PathVariable("id") Long id) {
        return Result.ok(reportService.adminDetail(id));
    }

    @Operation(summary = "证据媒体内容",
            description = "按工单 ID 回吐证据快照里的附件字节：图片/音视频内联渲染，其余文件强制下载；"
                    + "闪照已销毁回 7001。不走 im-file 的会话成员校验，权限门就是类级 system:report:handle——"
                    + "管理员天然不是会话成员，而判工单偏偏必须看到内容（与 FileController 同一手法：鉴权全在写第一个字节之前）")
    @GetMapping("/{id}/media")
    public void media(@Parameter(description = "工单 ID") @PathVariable("id") Long id,
                      HttpServletResponse response) throws IOException {
        FileDTO file = reportService.mediaOf(id);
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        boolean inline = contentType.startsWith("image/") || contentType.startsWith("video/")
                || contentType.startsWith("audio/") || contentType.equals("application/pdf");
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.getOriginalName() == null ? "evidence-" + file.getFileId() : file.getOriginalName(),
                        StandardCharsets.UTF_8)
                .build();
        response.setContentType(contentType);
        if (file.getSize() != null && file.getSize() > 0) {
            response.setContentLengthLong(file.getSize());
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (InputStream in = reportService.openMedia(file)) {
            StreamUtils.copy(in, response.getOutputStream());
        }
        response.flushBuffer();
    }

    @Operation(summary = "受理",
            description = "待处理 → 已受理，表示「有人在查」。不写处理时间，因此「已受理且处理时间为空」就是还没有结论；"
                    + "该状态纯人工推进，没有人受理就会一直停在待处理")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@Parameter(description = "工单 ID") @PathVariable("id") Long id) {
        reportService.accept(id, SecurityUtil.getUserId());
        dashboardService.evictCache();
        return Result.ok(null, "已受理");
    }

    @Operation(summary = "驳回",
            description = "判定举报不成立：只改工单状态，不动被举报对象，也不写 disposition")
    @PostMapping("/{id}/reject")
    public Result<String> reject(@Parameter(description = "工单 ID") @PathVariable("id") Long id,
                                 @RequestBody @Valid ReportRejectRequest request) {
        String message = reportService.reject(id, SecurityUtil.getUserId(), request.getRemark());
        dashboardService.evictCache();
        return Result.ok(message);
    }

    @Operation(summary = "处置",
            description = "执行一个动作并置为已处置：none/warn 由后台完成，ban_user/unban_user/recall_message/close_room "
                    + "经 SPI 交给对象归属模块执行。先执行动作再落工单状态，"
                    + "所以归属模块执行失败时工单仍停在原状态、原因原样回显（动作本身要求幂等，可安全重试）")
    @PostMapping("/{id}/handle")
    public Result<String> handle(@Parameter(description = "工单 ID") @PathVariable("id") Long id,
                                @RequestBody @Valid ReportHandleRequest request) {
        String message = reportService.handle(id, SecurityUtil.getUserId(), request);
        dashboardService.evictCache();
        return Result.ok(message);
    }
}
