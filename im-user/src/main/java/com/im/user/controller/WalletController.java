package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.req.PacketSendRequest;
import com.im.user.dto.req.RechargeRequest;
import com.im.user.dto.req.TransferRequest;
import com.im.user.dto.vo.PacketClaimVO;
import com.im.user.dto.vo.PacketVO;
import com.im.user.dto.vo.WalletActionVO;
import com.im.user.dto.vo.WalletVO;
import com.im.user.service.RedPacketService;
import com.im.user.service.TransferService;
import com.im.user.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 钱包接口：余额与礼物目录查询、模拟充值、每日签到、用户间转账与红包，全部需要登录。
 *
 * <p>充值、签到、转账与发红包都是易被脚本刷的入口，按用户维度限流；余额查询本身只读、开销小，不加配额。
 *
 * <p>转账与发红包返回 {@link WalletActionVO}（新余额 + 那条已落库的消息）而不是只回一个成功标记：
 * 12/13 型消息客户端不能自己 POST，这次调用就是它们唯一的产生点，不把消息体带回去，
 * 当前这一端就只能等 WebSocket 回放（而多端共享开关关掉时，用户会看到「点了转账但毫无反应」）。
 */
@Tag(name = "08-钱包", description = "虚拟积分余额、礼物目录、模拟充值、每日签到与用户间转账/红包")
@Validated
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@SaCheckLogin
public class WalletController {

    private final WalletService walletService;
    private final TransferService transferService;
    private final RedPacketService redPacketService;

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

    @Operation(summary = "单聊转账", description = "即时到账：仅单聊、仅好友，收款人由会话反查；金额上下界由服务端按配置校验")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "wallet.transfer")
    @PostMapping("/transfer")
    public Result<WalletActionVO> transfer(@RequestBody @Valid TransferRequest request) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(transferService.transfer(userId, request), "转账已到账");
    }

    @Operation(summary = "发红包", description = "单聊固定 1 份（对方独占），群聊按 count 拼手气；发出即从余额真扣，没人领则到期自动退回")
    @RateLimit(count = 10, seconds = 60, dimension = RateLimit.Dimension.USER, key = "wallet.packet.send")
    @PostMapping("/packet")
    public Result<WalletActionVO> sendPacket(@RequestBody @Valid PacketSendRequest request) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(redPacketService.send(userId, request), "红包已发出");
    }

    @Operation(summary = "红包详情", description = "点开气泡时现取领取态；已过期不报错，返回 status=expired")
    @GetMapping("/packet/{packetId}")
    public Result<PacketVO> packet(@PathVariable Long packetId) {
        return Result.ok(redPacketService.detail(SecurityUtil.getUserId(), packetId));
    }

    @Operation(summary = "抢红包", description = "一个名额只属于一个人，抢到即入账；重复领取返回「你已经领过了」")
    @RateLimit(count = 20, seconds = 10, dimension = RateLimit.Dimension.USER, key = "wallet.packet.claim")
    @PostMapping("/packet/{packetId}/claim")
    public Result<PacketClaimVO> claimPacket(@PathVariable Long packetId) {
        Long userId = SecurityUtil.getUserId();
        return Result.ok(redPacketService.claim(userId, packetId), "领取成功");
    }
}
