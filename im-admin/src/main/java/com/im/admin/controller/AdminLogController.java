package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.LogContentVO;
import com.im.admin.dto.vo.LogFileVO;
import com.im.admin.service.AdminLogService;
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

import java.util.List;

/**
 * 后台的日志管理（三期）。
 *
 * <p>权限点独立于监控（{@code system:log}）：日志原文可能含手机号、审核命中记录等
 * 敏感内容，能看指标曲线的人不必能看日志正文，这是这两页分开的全部理由。
 *
 * <p>安全口径见 {@code AdminLogService} 类注释：只认白名单 key、只尾读、不下载、
 * 关键词只在本页窗口内过滤。任何路径参数都不从外界接受。
 */
@Tag(name = "16-管理后台", description = "日志管理：分档日志的尾读与过滤")
@RestController
@RequestMapping("/api/admin/log")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_LOG)
public class AdminLogController {

    private final AdminLogService adminLogService;

    @Operation(summary = "日志文件清单",
            description = "七个逻辑档（debug/info/warn/error/slowSql/slowApi/moderation）的存在性、"
                    + "大小与最后修改时间；不存在的档照样列出（存在性为 false）")
    @GetMapping("/files")
    public Result<List<LogFileVO>> files() {
        return Result.ok(adminLogService.files());
    }

    @Operation(summary = "尾读日志",
            description = "读最近 tail 行（默认 200，上限 im.admin.log-tail-max-lines），"
                    + "关键词在尾读窗口内做不区分大小写过滤——不是全文件搜索；"
                    + "key 必须命中白名单，传路径一定 400")
    @GetMapping("/content")
    public Result<LogContentVO> content(
            @Parameter(description = "日志档：debug/info/warn/error/slowSql/slowApi/moderation")
            @RequestParam("key") String key,
            @Parameter(description = "尾读行数，服务端夹上限") @RequestParam(value = "tail", required = false) Integer tail,
            @Parameter(description = "窗口内过滤关键词，可空") @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(adminLogService.read(key, tail, keyword));
    }
}
