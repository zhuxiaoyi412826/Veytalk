package com.im.admin.controller;

import com.im.admin.dto.vo.PublicReleaseVO;
import com.im.admin.entity.ClientRelease;
import com.im.admin.service.ClientReleaseService;
import com.im.common.api.Result;
import com.im.common.domain.FileDTO;
import com.im.common.security.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * 官网下载页的公开端点（匿名可达，三条路径逐个列入 {@code SaTokenConfigure.EXCLUDE_PATTERNS}）。
 *
 * <p><b>字节层面的防枚举没松</b>：下载只认「启用中」的行，停用与不存在同回 404，
 * 不让探测者凭响应差异区分「曾经有过」与「从来没有」；列表也只回启用行。
 *
 * <p>新增的 {@code /history} 会把「某平台发过哪些版本（含已停用）」元信息列出来，
 * 这是对防枚举口径的一处<b>有意放宽</b>而不是疏忽：那条口径守的是安装包字节，
 * 而历史版本号本来就公开在 GitHub Releases 页上（官网下载区下面就是那条外链），
 * 藏起来不得到任何收益。停用行拿不到 downloadPath，就算拿着 ID 去探也只得到 404（详见 service 层注释）。
 *
 * <p>下载不复用 {@code /api/file/download}（那条要登录态或一次性票据）：官网访客两者都没有。
 * 安全口径改由三道门兜住：只递启用行 + 按 IP 的 {@code @RateLimit} + 响应头
 * {@code X-Content-Type-Options: nosniff}（exe/zip 一律 octet-stream，防浏览器嗅探成 HTML 执行）。
 */
@Tag(name = "17-官网公开", description = "客户端下载：官网下载页数据源与安装包匿名下载")
@RestController
@RequestMapping("/api/release")
@RequiredArgsConstructor
public class ReleaseController {

    private final ClientReleaseService clientReleaseService;

    @Operation(summary = "启用中的客户端版本列表",
            description = "官网下载页数据源：每个平台至多一行（启用互斥），按平台码表顺序排；"
                    + "一个平台都没启用时回空数组，官网回落静态文案")
    @RateLimit(count = 60, key = "release.list")
    @GetMapping("/list")
    public Result<List<PublicReleaseVO>> list() {
        return Result.ok(clientReleaseService.publicList());
    }

    @Operation(summary = "历史版本列表（含已停用）",
            description = "官网「历史版本」区块数据源：每平台取最近 N 条，按创建时间倒序；"
                    + "已停用行的 downloadPath 为空（站点只递启用行），不认识的平台码回空数组而不是全表")
    @RateLimit(count = 60, key = "release.history")
    @GetMapping("/history")
    public Result<List<PublicReleaseVO>> history(
            @Parameter(description = "平台码过滤：windows_setup / windows_portable / android / macos / linux；为空则每平台都取")
            @RequestParam(value = "platform", required = false) String platform,
            @Parameter(description = "每平台取几条，默认 5，上限 20")
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(clientReleaseService.publicHistory(platform, limit));
    }

    @Operation(summary = "下载安装包",
            description = "流式输出，Content-Disposition attachment（RFC 5987 编码文件名）；"
                    + "停用行与不存在的 ID 一律 404")
    @RateLimit(count = 30, key = "release.download")
    @GetMapping("/download/{id}")
    public void download(@Parameter(description = "版本 ID") @PathVariable("id") Long id,
                         HttpServletResponse response) throws IOException {
        ClientRelease row = clientReleaseService.requireDownloadable(id);
        FileDTO file = clientReleaseService.requireFileOf(row);
        InputStream in = clientReleaseService.openStream(file);
        try {
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(row.getFileName() == null ? "im-client" : row.getFileName(), StandardCharsets.UTF_8)
                    .build();
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            if (file.getSize() != null && file.getSize() > 0) {
                response.setContentLengthLong(file.getSize());
            }
            response.setHeader("X-Content-Type-Options", "nosniff");
            StreamUtils.copy(in, response.getOutputStream());
            response.flushBuffer();
        } finally {
            in.close();
        }
        // 计数放在字节成功写出之后：写一半断掉的下载不算一次有效下载
        clientReleaseService.countDownload(row.getId());
    }
}
