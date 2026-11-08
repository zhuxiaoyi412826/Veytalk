package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminAgreementService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminAgreementConsentDTO;
import com.im.common.domain.AdminAgreementConsentQueryDTO;
import com.im.common.domain.AdminAgreementDTO;
import com.im.common.domain.AdminAgreementQueryDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的协议管理：版本起草、草稿编辑、发布生效，以及同意留痕的两种查法。
 *
 * <p><b>这里没有任何删除端点</b>，与 {@code AdminArticleController} 最大的不同就在这儿：
 * 文章删了只是没人看，协议版本删了是销毁一份对用户生效过的合同原件。所以最重的动作只有
 * 「归档」，而且归档是发布的副产品，不是独立按钮——旧版本被新版本顶下去，文本仍然永久可查。
 *
 * <p>同样不提供「改已发布版本」：{@code PUT} 只认草稿。要让某份已生效文本变一点，
 * 唯一路径是发一个版本号更大的新版本并让所有已同意的人重新点一次头。这条限制看着死板，
 * 但它是「留痕里那个版本号到底证明了什么」这个问题的全部答案。
 *
 * <p>权限点单独一个 {@code system:agreement:manage}，不并进 {@code system:config} 也不给
 * {@code system:announcement}（理由写在 {@link ImConstants#PERM_SYSTEM_AGREEMENT_MANAGE}）。
 */
@Tag(name = "19-管理后台", description = "协议与同意留痕：版本起草/编辑/发布，同意记录翻页与个人同意链")
@RestController
@RequestMapping("/api/admin/agreement")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_AGREEMENT_MANAGE)
public class AdminAgreementController {

    private final AdminAgreementService adminAgreementService;

    @Operation(summary = "协议版本列表",
            description = "含全状态（草稿/已发布/已归档）；可按 code 与状态过滤，关键词按标题模糊；"
                    + "固定 code 升序 + version 倒序；列表项不含正文")
    @GetMapping("/page")
    public Result<PageResult<AdminAgreementDTO>> page(AdminAgreementQueryDTO query) {
        return Result.ok(adminAgreementService.page(query));
    }

    @Operation(summary = "版本详情", description = "含正文，供后台预览与草稿编辑回填")
    @GetMapping("/{id}")
    public Result<AdminAgreementDTO> detail(@Parameter(description = "版本行 ID") @PathVariable("id") Long id) {
        return Result.ok(adminAgreementService.detail(id));
    }

    @Operation(summary = "起草新版本",
            description = "在指定 code 下追加一个草稿，版本号由服务端取「历史最大 + 1」；"
                    + "required 缺省时隐私政策与用户协议为必签、服务条款为可拒")
    @PostMapping("/draft")
    public Result<AdminAgreementDTO> createDraft(@RequestBody AdminAgreementDTO dto) {
        return Result.ok(adminAgreementService.createDraft(dto, SecurityUtil.getUserId()), "草稿已创建");
    }

    @Operation(summary = "编辑草稿", description = "只能改草稿；已发布与已归档的版本不可修改（改了会让既有同意留痕变成伪证）")
    @PutMapping("/{id}")
    public Result<AdminAgreementDTO> updateDraft(@Parameter(description = "版本行 ID") @PathVariable("id") Long id,
                                                 @RequestBody AdminAgreementDTO dto) {
        dto.setId(id);
        return Result.ok(adminAgreementService.updateDraft(dto, SecurityUtil.getUserId()), "草稿已保存");
    }

    @Operation(summary = "发布生效",
            description = "<b>全端即时生效的高危动作</b>：草稿→已发布并盖生效时间，同 code 原生效版自动转归档。"
                    + "没有撤回——对外公示的合同文本只能被新版本顶替。发布后所有已同意过旧版的人会被要求重新同意")
    @PostMapping("/{id}/publish")
    public Result<AdminAgreementDTO> publish(@Parameter(description = "版本行 ID") @PathVariable("id") Long id,
                                             @Parameter(description = "本版变更说明") @RequestParam(value = "remark", required = false) String remark) {
        return Result.ok(adminAgreementService.publish(id, SecurityUtil.getUserId(), remark), "新版本已发布生效");
    }

    @Operation(summary = "同意留痕翻页",
            description = "按用户 / 版本行 / 协议标识 / 动作与时间范围组合查；固定同意时间倒序。"
                    + "拒签记录同样在这里（action=0），它是「这个人明确没同意过」的证据")
    @GetMapping("/consent/page")
    public Result<PageResult<AdminAgreementConsentDTO>> pageConsents(AdminAgreementConsentQueryDTO query) {
        return Result.ok(adminAgreementService.pageConsents(query));
    }

    @Operation(summary = "某个用户的完整同意链",
            description = "一屏看完他对三份协议的每一次表态（含版本号、时刻、IP、设备），"
                    + "监管与工单问到「他同意过哪一版」时给的就是这一份；不分页，最多 200 条")
    @GetMapping("/consent/user/{userId}")
    public Result<List<AdminAgreementConsentDTO>> userConsents(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminAgreementService.userConsents(userId));
    }
}
