package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminDatingService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminDatingQueryDTO;
import com.im.common.domain.AdminPhotoDTO;
import com.im.common.domain.AdminPhotoQueryDTO;
import com.im.common.domain.AdminProfileDTO;
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
 * 后台的交友资料与照片审核。
 *
 * <p>本类只持有 {@code system:dating:manage} 一个权限点，字典（标签 / 破冰）在
 * {@code AdminDictController}、动态在 {@code AdminMomentController}。拆成三个类而不是一个类
 * 挂方法级注解：Sa-Token 的类级与方法级 {@code @SaCheckPermission} 是<b>都要过</b>的关系，
 * 把 {@code tag:manage} 的端点塞进本类，只有交友权限的管理员就会连字典页也进不去。
 *
 * <p>「强制隐身」与「驳回照片」都复用用户自己那两列（{@code discoverable} / {@code audit_status}），
 * 不新增后台专用标记位，理由见 {@code AdminDatingSpi} 的方法注释。
 */
@Tag(name = "16-管理后台", description = "交友管理：资料翻页、强制隐身/恢复、推荐质量标记与照片墙审核")
@RestController
@RequestMapping("/api/admin/dating")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_DATING_MANAGE)
public class AdminDatingController {

    private final AdminDatingService adminDatingService;

    /* ==================== 交友资料 ==================== */

    @Operation(summary = "交友资料列表",
            description = "含隐身与已降权的人；关键词按城市/职业模糊匹配，纯数字时额外认用户 ID。"
                    + "固定按最近活跃倒序（活跃时间为空的排最后）。没有资料行的用户不出现——那等于没开过交友功能")
    @GetMapping("/profile/page")
    public Result<PageResult<AdminProfileDTO>> pageProfiles(AdminDatingQueryDTO query) {
        return Result.ok(adminDatingService.pageProfiles(query));
    }

    @Operation(summary = "强制隐身",
            description = "置 discoverable=0：不再进推荐流/附近的人/动态流，但已配对与好友关系不受影响。"
                    + "与用户自己关的开关同一列，所以本人也能重新打开——硬封走的是账号封禁，不是这一屏")
    @PostMapping("/profile/{userId}/hide")
    public Result<AdminProfileDTO> hide(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
                                        @Parameter(description = "事由，只进服务端审计日志") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminDatingService.setDiscoverable(userId, false, SecurityUtil.getUserId(), reason),
                "已强制隐身");
    }

    @Operation(summary = "恢复可被发现", description = "置 discoverable=1；幂等")
    @PostMapping("/profile/{userId}/reveal")
    public Result<AdminProfileDTO> reveal(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminDatingService.setDiscoverable(userId, true, SecurityUtil.getUserId(), null),
                "已恢复可被发现");
    }

    @Operation(summary = "调整推荐质量标记",
            description = "0 正常 1 低质降权（不进推荐流）2 已升权（真人认证，<b>只有本接口能写 2</b>，"
                    + "服务端任何完资料度重算都不会自动顶上去）；幂等")
    @PostMapping("/profile/{userId}/quality")
    public Result<AdminProfileDTO> quality(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
                                           @Parameter(description = "0 正常 1 低质降权 2 已升权") @RequestParam("flag") Integer flag,
                                           @Parameter(description = "事由，只进服务端审计日志") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminDatingService.setQualityFlag(userId, flag, SecurityUtil.getUserId(), reason),
                "质量标记已更新");
    }

    /* ==================== 照片墙审核 ==================== */

    @Operation(summary = "照片列表",
            description = "含待审与已驳回；固定按上传时间<b>升序</b>（先传来的先审，否则新图永远插队）。"
                    + "默认让前端停在 auditStatus=0 这一档，那才是需要人看的队列")
    @GetMapping("/photo/page")
    public Result<PageResult<AdminPhotoDTO>> pagePhotos(AdminPhotoQueryDTO query) {
        return Result.ok(adminDatingService.pagePhotos(query));
    }

    @Operation(summary = "照片通过",
            description = "置 audit_status=1 并清空上一次的驳回原因；只有这一档对外可见。"
                    + "通过后重算作者完资料度（照片那一档占分）；幂等")
    @PostMapping("/photo/{id}/approve")
    public Result<AdminPhotoDTO> approvePhoto(@Parameter(description = "照片记录 ID") @PathVariable("id") Long id) {
        return Result.ok(adminDatingService.auditPhoto(id, true, null, SecurityUtil.getUserId()), "照片已通过");
    }

    @Operation(summary = "照片驳回",
            description = "置 audit_status=2 并写原因：<b>保留行与原因</b>而不删——那是用户自己的图，"
                    + "他要知道哪张被拦才知道换一张，而不是反复重传同一张。原因只回给本人")
    @PostMapping("/photo/{id}/reject")
    public Result<AdminPhotoDTO> rejectPhoto(@Parameter(description = "照片记录 ID") @PathVariable("id") Long id,
                                             @Parameter(description = "驳回原因，只回给本人") @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminDatingService.auditPhoto(id, false, reason, SecurityUtil.getUserId()), "照片已驳回");
    }

    @Operation(summary = "删除照片",
            description = "逻辑删，与本人删除同一落点；用于重复上传与明显垃圾（内容不对但图还在走驳回）。"
                    + "im_file 里的文件本体一期不级联清理；删的正是主封面时不需要修 is_main，"
                    + "下一次读封面会自动退到下一张已过审的；幂等")
    @DeleteMapping("/photo/{id}")
    public Result<Void> deletePhoto(@Parameter(description = "照片记录 ID") @PathVariable("id") Long id) {
        adminDatingService.deletePhoto(id, SecurityUtil.getUserId());
        return Result.ok(null, "照片已删除");
    }
}
