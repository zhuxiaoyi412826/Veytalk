package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.AgreementConsentRequest;
import com.im.user.dto.vo.AgreementPendingVO;
import com.im.user.dto.vo.AgreementVO;
import com.im.user.service.AgreementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 法定文本的公示口与用户侧同意入口。
 *
 * <p>读的两条是<b>匿名</b>的，这是合规要求而不是便利：官网页脚那条《隐私政策》链接必须能在
 * 没登录的情况下打开，「你在收集我的信息之前就得告诉我你收集了什么」这条逻辑不能反过来
 * 要求用户先注册。它们已登记在 {@code SaTokenConfigure} 的白名单里。
 *
 * <p>写的两条都要求登录：注册时的首次同意走 {@code /api/auth/register} 的入参
 * （留痕与建号同时发生，不存在「先建号再补同意」的窗口），登录后改主意或文本改版才走这里。
 *
 * <p>历史版本永久可查（{@code ?version=}）：留痕里记着「他对 privacy v2 点过头」，
 * 而 v2 早被 v3 顶替了。这时候必须还有一个地方能把 v2 那份原文调出来，
 * 否则那条留痕证明的是一份谁也看不见的文本。
 */
@Tag(name = "19-协议与合规", description = "隐私政策 / 用户协议 / 服务条款的公示与同意留痕")
@RestController
@RequestMapping("/api/agreement")
@RequiredArgsConstructor
public class AgreementController {

    private final AgreementService agreementService;

    @Operation(summary = "协议清单",
            description = "全部当前生效版本的简表（不含正文），顺序固定为用户协议、隐私政策、服务条款；"
                    + "注册页与登录页一次拉齐三个链接与版本号，同意时原样回传各自的 version")
    @GetMapping("/list")
    public Result<List<AgreementVO>> list() {
        return Result.ok(agreementService.listPublished());
    }

    @Operation(summary = "协议正文",
            description = "匿名可读。不带 version 取当前生效版；带 version 取历史某版（归档版同样可查，草稿态查不到）")
    @Parameter(name = "code", description = "协议标识：terms / privacy / service")
    @GetMapping("/view/{code}")
    public Result<AgreementVO> view(@PathVariable("code") String code,
                                    @Parameter(description = "版本号，缺省取当前生效版")
                                    @RequestParam(value = "version", required = false) Integer version) {
        return Result.ok(agreementService.view(code, version, true));
    }

    @Operation(summary = "我的待同意清单",
            description = "文本改版、或账号建出来时还没走到同意这一步（验证码登录自动建号、"
                    + "本功能上线前注册的老账号）时返回待签项；dismissible=false 表示含必签项，弹窗不可关闭")
    @SaCheckLogin
    @GetMapping("/pending")
    public Result<AgreementPendingVO> pending() {
        return Result.ok(agreementService.pendingOf(SecurityUtil.getUserId()));
    }

    @Operation(summary = "提交同意",
            description = "登录后再同意弹窗的提交口；每项必须带当前生效版本号，"
                    + "版本对不上返回 2023，前端须重新展示新文本再取一次同意。可选项可显式传 accept=false 留一条拒签证据")
    @SaCheckLogin
    @RateLimit(count = 10, key = "agreement.consent")
    @PostMapping("/consent")
    public Result<Void> consent(@RequestBody @Valid AgreementConsentRequest request) {
        agreementService.consent(SecurityUtil.getUserId(), request.getAgreements());
        return Result.ok(null, "已记录你的同意");
    }
}
