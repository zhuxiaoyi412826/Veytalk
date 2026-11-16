package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.ReleaseQuery;
import com.im.admin.dto.req.ReleaseSaveRequest;
import com.im.admin.dto.vo.ReleaseVO;
import com.im.admin.enums.ReleasePlatform;
import com.im.admin.service.ClientReleaseService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台客户端版本管理。
 *
 * <p>独立权限点 {@code system:release}（ID 126），不并进 {@code system:config}：
 * 版本管理动的字节会直接成为官网的匿名下载口，「往公网上递一个 exe」与「调一个系统旋钮」
 * 的风险不是一个量级，应当可以授权给不同的人。执行迁移 SQL 后记得清 {@code im:auth:perm:*}
 * 缓存，否则 admin 在缓存到期前看不到新点（步骤写在 migration_client_release.sql）。
 *
 * <p>上传不经过本控制器：安装包字节走文件模块的分片上传（bizType=client_release，2GB 通道），
 * 这里只收 {@code fileId} 做登记——后台接口不需要为安装包单独放宽 multipart 上限。
 */
@Tag(name = "16-管理后台", description = "客户端版本：安装包登记、启用互斥与下线")
@RestController
@RequestMapping("/api/admin/release")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_RELEASE)
public class AdminReleaseController {

    private final ClientReleaseService clientReleaseService;

    @Operation(summary = "平台码表", description = "下拉选项由服务端码表给出（code+name），"
            + "前端不自造平台串：平台是启用互斥的分组键")
    @GetMapping("/platforms")
    public Result<List<Map<String, String>>> platforms() {
        List<Map<String, String>> list = Arrays.stream(ReleasePlatform.values())
                .map(p -> {
                    Map<String, String> item = new LinkedHashMap<>(2);
                    item.put("code", p.getCode());
                    item.put("name", p.getDisplayName());
                    return item;
                })
                .toList();
        return Result.ok(list);
    }

    @Operation(summary = "版本列表", description = "启用中的恒排最前，其余按创建时间倒序；"
            + "关键词在版本号与文件名上模糊匹配，可按平台/状态过滤")
    @GetMapping("/page")
    public Result<PageResult<ReleaseVO>> page(ReleaseQuery query) {
        return Result.ok(clientReleaseService.adminPage(query));
    }

    @Operation(summary = "版本详情")
    @GetMapping("/{id}")
    public Result<ReleaseVO> detail(@Parameter(description = "版本 ID") @PathVariable("id") Long id) {
        return Result.ok(clientReleaseService.detail(id));
    }

    @Operation(summary = "新建版本", description = "先经分片上传拿到 fileId 再登记；"
            + "enableNow=true 时同一事务内完成启用互斥（同平台原启用行自动停用）")
    @PostMapping
    public Result<ReleaseVO> create(@RequestBody @Valid ReleaseSaveRequest request) {
        ReleaseVO vo = clientReleaseService.create(request, SecurityUtil.getUserId());
        return Result.ok(vo, Boolean.TRUE.equals(request.getEnableNow()) ? "已新建并启用" : "已新建（未启用）");
    }

    @Operation(summary = "编辑版本", description = "可改版本号/更新说明/换安装包；平台不可改"
            + "（改平台等于删旧建新）。启用中的行允许换包——发现坏包立刻换，不用先停用")
    @PutMapping("/{id}")
    public Result<ReleaseVO> update(@Parameter(description = "版本 ID") @PathVariable("id") Long id,
                                    @RequestBody @Valid ReleaseSaveRequest request) {
        return Result.ok(clientReleaseService.update(id, request, SecurityUtil.getUserId()), "已保存");
    }

    @Operation(summary = "启用版本", description = "同平台同时只有一个启用行：本行立起来之前，"
            + "原启用行在同一个事务里被自动停用，官网下载页不会出现空窗")
    @PostMapping("/{id}/enable")
    public Result<ReleaseVO> enable(@Parameter(description = "版本 ID") @PathVariable("id") Long id) {
        return Result.ok(clientReleaseService.enable(id, SecurityUtil.getUserId()),
                "已启用（同平台原启用版本已自动停用）");
    }

    @Operation(summary = "停用版本", description = "官网该平台下载卡即刻消失，是「下线一个坏包」的止损动作")
    @PostMapping("/{id}/disable")
    public Result<ReleaseVO> disable(@Parameter(description = "版本 ID") @PathVariable("id") Long id) {
        return Result.ok(clientReleaseService.disable(id, SecurityUtil.getUserId()), "已停用");
    }

    @Operation(summary = "删除版本", description = "仅停用态可删；物理删行但不删存储字节"
            + "（同 MD5 对象可能被别的行秒传复用）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "版本 ID") @PathVariable("id") Long id) {
        clientReleaseService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "已删除");
    }
}
