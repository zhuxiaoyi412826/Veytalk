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
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 官网下载页的公开端点（匿名可达，路径已列入 {@code SaTokenConfigure.EXCLUDE_PATTERNS}）。
 *
 * <p>只有两条：列表只回「启用中」的行，下载只认「启用中」的行——停用与不存在同回 404，
 * 不让探测者凭响应差异枚举历史版本 ID。
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
