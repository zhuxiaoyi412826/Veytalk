package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminVideoService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminVideoDTO;
import com.im.common.domain.AdminVideoQueryDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的视频管理（三期）。
 *
 * <p>下架与恢复拆成两个 POST 而不是一个带 boolean 的 PUT，沿用
 * {@code AdminUserController} 的 disable/enable 分路口径：URL 自己说明在做什么，
 * 审计日志里不出现要靠参数值才读得懂的行。
 *
 * <p><b>删除是逻辑删除且不可自助恢复</b>（一期没有回收站页），所以前端把删除做成
 * 需要二次确认的高危动作；这里不做「删了还能查」的接口——{@code deleted=1} 的行
 * MyBatis-Plus 全局过滤掉了，恢复要走数据库操作，本来就不该是后台按钮。
 */
@Tag(name = "16-管理后台", description = "视频管理：短视频翻页、下架/恢复与删除")
@RestController
@RequestMapping("/api/admin/video")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_VIDEO_MANAGE)
public class AdminVideoController {

    private final AdminVideoService adminVideoService;

    @Operation(summary = "视频列表",
            description = "含已下架（status=2）；关键词按标题模糊匹配，纯数字时额外认上传者 ID；"
                    + "固定按发布时间倒序。注意 status 只有 1/2 两值，删除后的行整体隐身")
    @GetMapping("/page")
    public Result<PageResult<AdminVideoDTO>> page(AdminVideoQueryDTO query) {
        return Result.ok(adminVideoService.page(query));
    }

    @Operation(summary = "下架视频",
            description = "置 status=2，信息流不再展示但记录保留；必须带 reason 进服务端审计日志")
    @PostMapping("/{id}/offline")
    public Result<AdminVideoDTO> offline(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id,
                                         @Parameter(description = "下架事由") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminVideoService.setStatus(id, false, SecurityUtil.getUserId(), reason), "视频已下架");
    }

    @Operation(summary = "恢复上架", description = "下架误操作或整改完成后置回 status=1；幂等")
    @PostMapping("/{id}/online")
    public Result<AdminVideoDTO> online(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id) {
        return Result.ok(adminVideoService.setStatus(id, true, SecurityUtil.getUserId(), null), "视频已恢复上架");
    }

    @Operation(summary = "删除视频",
            description = "逻辑删除（deleted=1），上传者本人删与管理员删同一落点；文件本体一期不级联清理；幂等")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "视频记录 ID") @PathVariable("id") Long id) {
        adminVideoService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "视频已删除");
    }
}
