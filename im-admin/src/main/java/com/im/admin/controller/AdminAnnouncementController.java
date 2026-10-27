package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.AnnouncementQuery;
import com.im.admin.dto.req.AnnouncementSaveRequest;
import com.im.admin.dto.vo.AnnouncementVO;
import com.im.admin.service.AnnouncementService;
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

/**
 * 后台全服公告（四期第四批）。
 *
 * <p>这是一个<b>新权限点</b> {@code system:announcement}（ID 118），没有并进 {@code system:config}：
 * 改一个全服旋钮改错了可以立刻改回来，而发公告是对着所有在线连接喊话，弹出去的通知收不回来
 * （撤回只影响之后的补拉）。两者的可撤销性不是一个量级，所以分成两个点，
 * 让「能调开关」与「能发公告」可以是两个人。
 *
 * <p>也正因为新开了一个点，<b>执行 SQL 后必须清一次 {@code im:auth:*} 权限缓存</b>，
 * 否则 admin 角色在缓存到期前仍然看不到这个新点、进来就报无权限——那与「没插权限行」是同一种表现，
 * 而排障方向完全不同。清理步骤写在 {@code sql/migration_admin_system_config.sql} 里。
 *
 * <p>五个动作各占一个端点而不是一个 {@code PUT /status}：草稿、发布、撤回、删除之间的迁移规则
 * 完全不同（发布要广播、撤回不回收、删除只允许草稿），合成一个接口就得在 body 里塞一个意图字段，
 * 然后写一堆 if 去猜调用方想干什么。
 */
@Tag(name = "16-管理后台", description = "系统配置：全服公告的编辑、发布与撤回")
@RestController
@RequestMapping("/api/admin/announcement")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_ANNOUNCEMENT)
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "公告列表",
            description = "按状态筛选（0 草稿 1 已发布 2 已撤回），关键词在标题与正文上做模糊匹配，按创建时间倒序")
    @GetMapping("/page")
    public Result<PageResult<AnnouncementVO>> page(AnnouncementQuery query) {
        return Result.ok(announcementService.adminPage(query));
    }

    @Operation(summary = "公告详情")
    @GetMapping("/{id}")
    public Result<AnnouncementVO> detail(@Parameter(description = "公告 ID") @PathVariable("id") Long id) {
        return Result.ok(announcementService.detail(id));
    }

    @Operation(summary = "新建公告",
            description = "publishNow=false 只存草稿；true 时同一个调用里落库为已发布并向全部在线连接广播一帧 "
                    + "notify(action=announce)。标题与正文都先过后台自己的敏感词库，"
                    + "否则「把敏感词写进公告」就成了绕过过滤器的正门")
    @PostMapping
    public Result<AnnouncementVO> create(@RequestBody @Valid AnnouncementSaveRequest request) {
        AnnouncementVO vo = announcementService.save(request, SecurityUtil.getUserId());
        return Result.ok(vo, Boolean.TRUE.equals(request.getPublishNow()) ? "已发布并广播" : "已保存为草稿");
    }

    @Operation(summary = "编辑公告", description = "仅草稿可编辑：改一条已广播出去的正文，"
            + "等于让在线的人看旧文本、之后登录的人看新文本，两批人都会以为自己是「看到公告的那个人」")
    @PutMapping("/{id}")
    public Result<AnnouncementVO> update(@Parameter(description = "公告 ID") @PathVariable("id") Long id,
                                         @RequestBody @Valid AnnouncementSaveRequest request) {
        return Result.ok(announcementService.update(id, request, SecurityUtil.getUserId()), "草稿已更新");
    }

    @Operation(summary = "发布草稿",
            description = "草稿 → 已发布。先落库再广播：广播失败只记日志、状态仍是已发布，"
                    + "离线与之后登录的用户靠补拉拿到")
    @PostMapping("/{id}/publish")
    public Result<AnnouncementVO> publish(@Parameter(description = "公告 ID") @PathVariable("id") Long id) {
        return Result.ok(announcementService.publish(id, SecurityUtil.getUserId()), "已发布并广播");
    }

    @Operation(summary = "撤回公告",
            description = "已发布 → 已撤回，之后的补拉不再返回它；<b>已经弹出去的那条通知收不回来</b>（已知边界）")
    @PostMapping("/{id}/revoke")
    public Result<AnnouncementVO> revoke(@Parameter(description = "公告 ID") @PathVariable("id") Long id) {
        return Result.ok(announcementService.revoke(id, SecurityUtil.getUserId()),
                "已撤回（已经推送的通知无法收回）");
    }

    @Operation(summary = "删除草稿", description = "仅草稿可删。已发布与已撤回的行留下，"
            + "它们是「什么时候说过什么话」的台账")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "公告 ID") @PathVariable("id") Long id) {
        announcementService.delete(id, SecurityUtil.getUserId());
        return Result.ok(null, "草稿已删除");
    }
}
