package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminInterviewService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminInterviewOverviewDTO;
import com.im.common.domain.AdminInterviewQueryDTO;
import com.im.common.domain.AdminInterviewSessionDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的 AI 面试记录（四期第五批）。
 *
 * <p>三个端点<b>全是 GET</b>，一个写动作都没有，而且不是「还没来得及做」：
 * {@code im_interview_session} 上那些违规计数是<b>判罚依据</b>——「违规达阈值强制结束」
 * 这句话成立的前提就是「这些数字是当时由写侧一口气加出来的、事后没人碰过」；
 * {@code im_interview_event} 的表注释写死只增不改不删。真要中止一场正在作弊的面试，
 * 正当路径是去 {@code /api/admin/user} 禁用那个候选人的账号，那是一次有权限、有留痕、
 * 有理由的处置。顺带说清：禁用账号<b>不会让那一屏立刻停下</b>（面试页是纯前端的，
 * 它只会在下一次上报时被鉴权拦住），这与远程控制那一屏的边界同理。
 *
 * <p>权限单设 {@code system:interview}：这一屏读的是应聘者的作答原文，
 * 能禁号的人不必能看到他答了什么题、怎么答的。
 *
 * <p>概览里的 {@code staleOngoing} 是「进行中但开始时间已超过合理时长」的僵尸行数，
 * <b>纯提示、不自动改状态</b>：把别人的面试判成「未完成」是一次写操作，
 * 而写操作的判断标准应该由发起方拿。
 */
@Tag(name = "16-管理后台", description = "AI 面试记录：场次台账、作答原文与违规审计（只读）")
@RestController
@RequestMapping("/api/admin/interview")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_INTERVIEW)
public class AdminInterviewController {

    private final AdminInterviewService adminInterviewService;

    @Operation(summary = "场次台账",
            description = "含进行中、已结束与违规强停的全部场次；进行中的恒排最前，其余按建档时间倒序。"
                    + "列表行不带问答与事件正文（content 是 TEXT，二十行各带一份会把响应撑爆），"
                    + "要看细节走单场档案。violationOnly 与 minViolations 无索引，默认不勾")
    @GetMapping("/page")
    public Result<PageResult<AdminInterviewSessionDTO>> page(AdminInterviewQueryDTO query) {
        return Result.ok(adminInterviewService.page(query));
    }

    @Operation(summary = "单场档案",
            description = "计数 + 违规按类型拆分 + 逐轮问答原文 + 事件流水（条数服务端夹死）。"
                    + "这是唯一交出作答原文的路径，读一次在后端留一条含场次 ID 的日志。"
                    + "场次不存在或已过期回 10001")
    @GetMapping("/{sessionId}")
    public Result<AdminInterviewSessionDTO> detail(
            @Parameter(description = "面试场次 ID") @PathVariable("sessionId") Long sessionId) {
        return Result.ok(adminInterviewService.detail(sessionId));
    }

    @Operation(summary = "概览数字",
            description = "总场次 / 今日新建 / 进行中 / 僵尸进行中 / 违规强停（全历史与今日）六个计数，"
                    + "再加违规率（分母只含已结束场次）与违规榜前 10 人。六个计数一条带 CASE 的聚合拿全，"
                    + "日界由 MySQL 的 CURDATE() 定，不传应用侧时间戳")
    @GetMapping("/overview")
    public Result<AdminInterviewOverviewDTO> overview() {
        return Result.ok(adminInterviewService.overview());
    }
}
