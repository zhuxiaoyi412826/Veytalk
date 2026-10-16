package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.RechargeRequest;
import com.im.user.dto.vo.WalletVO;
import com.im.user.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包接口：余额与礼物目录查询、模拟充值、每日签到，全部需要登录。
 *
 * <p>充值与签到是易被脚本刷的入口，按用户维度限流；余额查询本身只读、开销小，不加配额。
 */
@Tag(name = "08-钱包", description = "虚拟积分余额、礼物目录、模拟充值与每日签到")
@Validated
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@SaCheckLogin
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "查询本人钱包", description = "返回余额 + 上架礼物目录 + 签到/充值相关配置")
    @GetMapping
    public Result<WalletVO> wallet() {
        return Result.ok(walletService.getWallet(SecurityUtil.getUserId()));
    }

    @Operation(summary = "充值积分", description = "本轮走模拟支付桩直接到账；金额上下界由服务端按配置校验")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "wallet.recharge")
    @PostMapping("/recharge")
    public Result<WalletVO> recharge(@RequestBody @Valid RechargeRequest request) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(walletService.recharge(userId, request.getAmount()), "充值已到账");
    }

    @Operation(summary = "每日签到", description = "按自然日去重赠送积分，重复签到返回「今日已签到」")
    @RateLimit(count = 5, seconds = 60, dimension = RateLimit.Dimension.USER, key = "wallet.checkin")
    @PostMapping("/checkin")
    public Result<WalletVO> checkin() {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(walletService.checkin(userId), "签到成功");
    }
}
