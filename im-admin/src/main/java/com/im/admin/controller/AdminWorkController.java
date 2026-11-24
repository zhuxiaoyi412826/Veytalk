package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.admin.service.AdminWorkService;
import com.im.admin.service.WorkAuthGuard;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.domain.AdminWorkCreateDTO;
import com.im.common.domain.AdminWorkDTO;
import com.im.common.domain.AdminWorkQueryDTO;
import com.im.common.domain.AdminWorkUpdateDTO;
import com.im.common.enums.WorkBoardType;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的作品管理（图片 / 音乐 / 小说），一套端点按 {@code {type}} 分发。
 *
 * <p><b>权限是动态的</b>：类上只有 {@code @SaCheckLogin}，每个方法首行调
 * {@link WorkAuthGuard#check(String)} 把 {@code type} 映射成 129/130/131 三个权限点之一再校验。
 * 少了这一行，「只有图片权限的管理员」就能从 URL 上换个字进小说那一屏——注解做不到这件事，
 * 因为注解里的权限串必须是编译期常量。
 *
 * <p>{@code type} 一律以 URL 为准：body 里的 {@code dto.type} 与查询串里的 {@code query.type}
 * 都会被守卫返回的规范化 code 覆盖掉，不给「URL 说 image、body 说 novel」留出裁决分歧。
 *
 * <p>下架与恢复拆成两个 POST 而不是一个带 boolean 的 PUT，沿用 {@code AdminVideoController}
 * 的口径：URL 自己说明在做什么，审计日志里不出现要靠参数值才读得懂的行。
 */
@Tag(name = "16-管理后台", description = "作品管理：图片/音乐/小说翻页、代建、编辑、下架/恢复与删除")
@RestController
@RequestMapping("/api/admin/work")
@RequiredArgsConstructor
@SaCheckLogin
public class AdminWorkController {

    private final AdminWorkService adminWorkService;
    private final WorkAuthGuard workAuthGuard;

    @Operation(summary = "作品列表",
            description = "含已下架（status=2）；关键词按标题模糊匹配，纯数字时额外认作品 ID 或作者 ID；固定按发布时间倒序。"
                    + "小说列表<b>不含正文</b>，正文只在详情返回")
    @GetMapping("/{type}/page")
    public Result<PageResult<AdminWorkDTO>> page(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                                 AdminWorkQueryDTO query) {
        WorkBoardType board = workAuthGuard.check(type);
        query.setType(board.getCode());
        return Result.ok(adminWorkService.page(query));
    }

    @Operation(summary = "作品详情",
            description = "后台详情不拒已下架的行（运营要能打开刚下架的那一条核对）；小说带 Markdown 正文，供编辑弹窗回填")
    @GetMapping("/{type}/{id}")
    public Result<AdminWorkDTO> detail(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                       @Parameter(description = "作品记录 ID") @PathVariable("id") Long id) {
        workAuthGuard.check(type);
        return Result.ok(adminWorkService.detail(type, id));
    }

    @Operation(summary = "代建作品",
            description = "管理员代用户新建一条作品，<b>发布即可见</b>（status=1，不走文章的审核状态机）；"
                    + "归属记在当前管理员名下。媒体先经 /api/file/upload 上传（bizType=image_work / music / "
                    + "music_cover / novel_cover）拿到 fileId 再提交，受控地址由服务端拼")
    @PostMapping("/{type}")
    public Result<AdminWorkDTO> create(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                       @RequestBody AdminWorkCreateDTO dto) {
        WorkBoardType board = workAuthGuard.check(type);
        dto.setType(board.getCode());
        return Result.ok(adminWorkService.create(board.getCode(), dto, SecurityUtil.getUserId()), "作品已发布");
    }

    @Operation(summary = "编辑作品",
            description = "只改传了值的字段（null 表示不改），<b>不改归属、不改状态</b>；"
                    + "coverFileId 传 0 表示去掉封面；图集 imageIds 传了就是整组替换")
    @PutMapping("/{type}/{id}")
    public Result<AdminWorkDTO> update(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                       @Parameter(description = "作品记录 ID") @PathVariable("id") Long id,
                                       @RequestBody AdminWorkUpdateDTO dto) {
        workAuthGuard.check(type);
        return Result.ok(adminWorkService.update(type, id, dto, SecurityUtil.getUserId()), "作品已保存");
    }

    @Operation(summary = "下架作品",
            description = "置 status=2：用户端信息流与详情即刻不可见，后台列表仍可见；reason 只进服务端审计日志")
    @PostMapping("/{type}/{id}/offline")
    public Result<AdminWorkDTO> offline(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                        @Parameter(description = "作品记录 ID") @PathVariable("id") Long id,
                                        @Parameter(description = "下架事由") @RequestParam(value = "reason", required = false) String reason) {
        workAuthGuard.check(type);
        return Result.ok(adminWorkService.setStatus(type, id, false, SecurityUtil.getUserId(), reason), "作品已下架");
    }

    @Operation(summary = "恢复上架", description = "下架误操作或整改完成后置回 status=1；幂等")
    @PostMapping("/{type}/{id}/online")
    public Result<AdminWorkDTO> online(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                                       @Parameter(description = "作品记录 ID") @PathVariable("id") Long id) {
        workAuthGuard.check(type);
        return Result.ok(adminWorkService.setStatus(type, id, true, SecurityUtil.getUserId(), null), "作品已恢复上架");
    }

    @Operation(summary = "删除作品",
            description = "逻辑删除（deleted=1），与作者本人删除同一落点；im_file 里的媒体本体一期不级联清理；幂等")
    @DeleteMapping("/{type}/{id}")
    public Result<Void> delete(@Parameter(description = "板块：image / music / novel") @PathVariable("type") String type,
                               @Parameter(description = "作品记录 ID") @PathVariable("id") Long id) {
        workAuthGuard.check(type);
        adminWorkService.delete(type, id, SecurityUtil.getUserId());
        return Result.ok(null, "作品已删除");
    }
}
