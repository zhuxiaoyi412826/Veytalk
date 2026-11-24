package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminMomentService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminCommentQueryDTO;
import com.im.common.domain.AdminMomentCommentDTO;
import com.im.common.domain.AdminMomentDTO;
import com.im.common.domain.AdminMomentQueryDTO;
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
 * 后台动态管理：动态全量翻页/详情/下架/删除，以及跨动态的评论队列。
 *
 * <p>独立的权限点 133 {@code system:moment:manage}，与 {@code AdminDatingController} 分开类的
 * 理由同 {@code AdminDictController} 的注释（Sa-Token 类级与方法级是「都要过」）。
 *
 * <p>「下架」与「删除」是两个端点而不是一个带 boolean 的：删除会连带清掉媒体、评论、点赞、
 * 可见范围白名单四张附属表且<b>不可恢复</b>，误删成本远高于下架。把它们做成两个动作，
 * 前端就是两个按钮两次确认，而不是一个开关拨错就没了。
 */
@Tag(name = "16-管理后台", description = "动态管理：动态下架/恢复/删除与评论队列")
@RestController
@RequestMapping("/api/admin/moment")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_MOMENT_MANAGE)
public class AdminMomentController {

    private final AdminMomentService adminMomentService;

    /* ==================== 动态 ==================== */

    @Operation(summary = "动态列表",
            description = "含已下架的（后台要能追回）；关键词只按正文模糊匹配。"
                    + "固定按发布时间倒序。媒体只回前 3 张做缩略预览，正文也截断——列表页不需要全量")
    @GetMapping("/page")
    public Result<PageResult<AdminMomentDTO>> page(AdminMomentQueryDTO query) {
        return Result.ok(adminMomentService.page(query));
    }

    @Operation(summary = "动态详情",
            description = "返回完整正文与全部媒体。<b>已下架的也能查</b>——处理申诉时正是要看那条被藏起来的内容原样")
    @GetMapping("/{id}")
    public Result<AdminMomentDTO> detail(@Parameter(description = "动态 ID") @PathVariable("id") Long id) {
        return Result.ok(adminMomentService.detail(id));
    }

    @Operation(summary = "下架动态",
            description = "置 status=2：<b>不再出现在任何人的 Feed 与详情里（含作者本人）</b>，但行与附属表全部保留、随时可恢复。"
                    + "幂等——重复下架返回当前态而不报错")
    @PostMapping("/{id}/offline")
    public Result<AdminMomentDTO> offline(@Parameter(description = "动态 ID") @PathVariable("id") Long id,
                                          @Parameter(description = "事由，只进服务端审计日志") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminMomentService.setStatus(id, false, SecurityUtil.getUserId(), reason), "动态已下架");
    }

    @Operation(summary = "恢复动态", description = "置 status=1；幂等")
    @PostMapping("/{id}/online")
    public Result<AdminMomentDTO> online(@Parameter(description = "动态 ID") @PathVariable("id") Long id) {
        return Result.ok(adminMomentService.setStatus(id, true, SecurityUtil.getUserId(), null), "动态已恢复");
    }

    @Operation(summary = "删除动态",
            description = "逻辑删，并<b>连带删除媒体/评论/点赞/可见范围白名单</b>四张附属表，与用户端删自己动态同一落点；"
                    + "im_file 里的文件本体一期不级联清理。不可恢复，一般情况请走下架")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "动态 ID") @PathVariable("id") Long id) {
        adminMomentService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "动态已删除");
    }

    /* ==================== 评论队列 ==================== */

    @Operation(summary = "评论列表",
            description = "跨动态的一级评论队列，不传 momentId 就是全站待处理评论。"
                    + "含已下架动态下的评论（那往往是举报的来源）；固定按创建时间倒序")
    @GetMapping("/comment/page")
    public Result<PageResult<AdminMomentCommentDTO>> pageComments(AdminCommentQueryDTO query) {
        return Result.ok(adminMomentService.pageComments(query));
    }

    @Operation(summary = "下架评论",
            description = "连带藏掉它当前的回复，并同步回写动态的评论数——只藏父行会留下一批找不到父的回复，"
                    + "列表计数与实际条数就永久对不上。恢复时<b>只恢复被点的这一条</b>，不连带子树")
    @PostMapping("/comment/{id}/offline")
    public Result<AdminMomentCommentDTO> offlineComment(@Parameter(description = "评论 ID") @PathVariable("id") Long id,
                                                        @Parameter(description = "事由，只进服务端审计日志") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminMomentService.setCommentStatus(id, false, SecurityUtil.getUserId(), reason), "评论已下架");
    }

    @Operation(summary = "恢复评论",
            description = "只恢复被点的这一条。它的回复若仍处于下架态，那是被单独处置过，连带恢复等于抹掉别人的一次处置")
    @PostMapping("/comment/{id}/online")
    public Result<AdminMomentCommentDTO> onlineComment(@Parameter(description = "评论 ID") @PathVariable("id") Long id) {
        return Result.ok(adminMomentService.setCommentStatus(id, true, SecurityUtil.getUserId(), null), "评论已恢复");
    }

    @Operation(summary = "删除评论",
            description = "逻辑删并连带删除它下面的全部回复，评论数按<b>本次真正删掉的行数</b>回退。不可恢复")
    @DeleteMapping("/comment/{id}")
    public Result<Void> deleteComment(@Parameter(description = "评论 ID") @PathVariable("id") Long id) {
        adminMomentService.deleteComment(id, SecurityUtil.getUserId());
        return Result.ok(null, "评论已删除");
    }
}
