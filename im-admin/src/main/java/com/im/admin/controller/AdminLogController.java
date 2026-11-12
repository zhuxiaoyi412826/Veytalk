package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.vo.LogArchiveVO;
import com.im.admin.dto.vo.LogContentVO;
import com.im.admin.dto.vo.LogFileVO;
import com.im.admin.service.AdminLogService;
import com.im.admin.service.AdminModerationService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminModerationLogDTO;
import com.im.common.domain.AdminModerationQueryDTO;
import com.im.common.domain.FileDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 后台的日志管理（三期）。
 *
 * <p>权限点独立于监控（{@code system:log}）：日志原文可能含手机号、审核命中记录等
 * 敏感内容，能看指标曲线的人不必能看日志正文，这是这两页分开的全部理由。
 *
 * <p>安全口径见 {@code AdminLogService} 类注释：只认白名单 key、只尾读、不下载、
 * 关键词只在本页窗口内过滤。任何路径参数都不从外界接受。
 *
 * <p>唯一的例外是下面那个 {@code /moderation/{id}/image}：它的 {@code id} 是来自外界的
 * 审核记录主键，但它不拼路径、只走 SPI 查库，取不到就是 NOT_FOUND，与文件系统无关。
 */
@Tag(name = "16-管理后台", description = "日志管理：分档日志的尾读与过滤")
@RestController
@RequestMapping("/api/admin/log")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_LOG)
public class AdminLogController {

    private final AdminLogService adminLogService;
    private final AdminModerationService adminModerationService;

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
                    + "key 必须命中白名单，传路径一定 400。带 date（yyyy-MM-dd，严格校验）时读"
                    + "对应归档（五期·路线 1），index 选同日分段；归档不存在回 exists=false 而非报错")
    @GetMapping("/content")
    public Result<LogContentVO> content(
            @Parameter(description = "日志档：debug/info/warn/error/slowSql/slowApi/moderation")
            @RequestParam("key") String key,
            @Parameter(description = "尾读行数，服务端夹上限") @RequestParam(value = "tail", required = false) Integer tail,
            @Parameter(description = "窗口内过滤关键词，可空") @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "归档日期 yyyy-MM-dd，空则读当天活动文件") @RequestParam(value = "date", required = false) String date,
            @Parameter(description = "同日内分段序号，仅随 date 生效，缺省 0") @RequestParam(value = "index", required = false) Integer index) {
        return Result.ok(adminLogService.read(key, tail, keyword, date, index));
    }

    @Operation(summary = "归档清单",
            description = "列出某档已滚存的带日期归档（logback 跨天产生，保留期 im.log.max-history 到期即消）；"
                    + "只回受格式校验的 date+index 结构化值，文件名仅作展示，回传路径依旧拒绝")
    @GetMapping("/archives")
    public Result<List<LogArchiveVO>> archives(
            @Parameter(description = "日志档 key") @RequestParam("key") String key) {
        return Result.ok(adminLogService.archives(key));
    }

    @Operation(summary = "审核记录分页",
            description = "图片审核命中流水（im_moderation_log，五期·路线 2）按 用户/场景/类别/时间 翻页，"
                    + "主键倒序（雪花即时间序），单页上限 50；与 moderation.log 双写同源，"
                    + "文件只留 30 天而表不限——迁表就是为了这张页能翻过保留期")
    @GetMapping("/moderation/page")
    public Result<PageResult<AdminModerationLogDTO>> moderationPage(AdminModerationQueryDTO query) {
        return Result.ok(adminModerationService.page(query));
    }

    @Operation(summary = "审核记录的图片内容",
            description = "回吐一条命中记录的隔离区图片字节（发送端拦下后另传的那份副本）：永远内联渲染，"
                    + "因为这一类只收图片扩展名。无图时回 1004 并带原因（隔离区开关 / 图超大 / 上传失败 / 未跑迁移）。"
                    + "不走 im-file 的会话成员校验，权限门就是类级 system:log——管理员既不是上传者也不是会话成员，"
                    + "而判「拦得对不对」必须看到图（与举报证据端点同一手法：鉴权全在写第一个字节之前）")
    @GetMapping("/moderation/{id}/image")
    public void moderationImage(@Parameter(description = "审核记录 ID") @PathVariable("id") Long id,
                                HttpServletResponse response) throws IOException {
        FileDTO file = adminModerationService.evidenceFile(id);
        String contentType = file.getContentType() == null ? "image/jpeg" : file.getContentType();
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(file.getOriginalName() == null ? "evidence-" + file.getFileId() + ".jpg" : file.getOriginalName(),
                        StandardCharsets.UTF_8)
                .build();
        response.setContentType(contentType);
        if (file.getSize() != null && file.getSize() > 0) {
            response.setContentLengthLong(file.getSize());
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        // 字节来自被拦下的那张图，内容类型只按扩展名反查过（见 FileConvert#safeContentType）；
        // nosniff 是最后一道保险，不让浏览器自己「猜」成可执行内容来渲染
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try (InputStream in = adminModerationService.openEvidence(file)) {
            StreamUtils.copy(in, response.getOutputStream());
        }
        response.flushBuffer();
    }
}
