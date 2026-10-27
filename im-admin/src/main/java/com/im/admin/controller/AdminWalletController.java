package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminWalletService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminGiftDTO;
import com.im.common.domain.AdminGiftRecordDTO;
import com.im.common.domain.AdminGiftRecordQueryDTO;
import com.im.common.domain.AdminWalletDTO;
import com.im.common.domain.AdminWalletOverviewDTO;
import com.im.common.domain.AdminWalletQueryDTO;
import com.im.common.domain.AdminWalletTxDTO;
import com.im.common.domain.AdminWalletTxQueryDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的钱包与礼物（四期第五批）。
 *
 * <p>三个页签一张屏：钱包台账（含调账）、送礼流水、礼物目录（含上下架）。
 * 权限单设 {@code system:wallet} 而不并进 {@code system:user:manage}——
 * 管一个人的「能不能登录」和管他的「余额是多少」是两种风险：前者错了可以恢复，
 * 后者错了是<b>凭空造出货币</b>。
 *
 * <p>唯一的写动作是 {@code adjust}，它没有「只改数字不记一笔」这条路：
 * 每次调用都在 im-user 内复用 {@code WalletService} 的 {@code credit}/{@code debit}
 * （条件 UPDATE + 同事务补 {@code balance_after} 流水），事由必填、操作人进备注。
 * 扣减不能把余额调成负数——那条不变量是账本可信赖的前提，为管理员开后门等于取消它。
 *
 * <p>送礼流水<b>一个写动作都没有</b>：那是账本，改一笔等于篡改他人之间的交易记录。
 * 礼物目录也只改上下架，不改价、不改名、不删行（历史流水冗余了礼物名与成交单价，
 * 改价会让「今天卖 100、明天卖 10」变成两个人各执一词）。
 *
 * <p>{@code im_user_wallet} 与 {@code im_wallet_transaction} 的索引情况决定了
 * 排序与单页上限都由服务端写死，前端传不动它——理由写在对应的 DTO 类注释里。
 */
@Tag(name = "16-管理后台", description = "钱包与礼物：账本、流水、调账、礼物目录")
@RestController
@RequestMapping("/api/admin/wallet")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_WALLET)
public class AdminWalletController {

    private final AdminWalletService adminWalletService;

    @Operation(summary = "概览数字",
            description = "钱包开关（运行态）、开户数、全站累计充值/消费/余额、今日流水笔数与净额、"
                    + "其中后台调账几笔。那几个 SUM 是全表扫描（这张表除主键无索引），"
                    + "行数等于用户数所以可接受，后端无缓存也不轮询——手点刷新")
    @GetMapping("/overview")
    public Result<AdminWalletOverviewDTO> overview() {
        return Result.ok(adminWalletService.overview());
    }

    @Operation(summary = "钱包台账",
            description = "排序固定「余额高的在前」（同额按用户 ID 升序，避免翻页出现重复行）；"
                    + "keyword 同时匹配昵称与用户 ID 数字。响应绝不含支付凭证类字段")
    @GetMapping("/page")
    public Result<PageResult<AdminWalletDTO>> page(AdminWalletQueryDTO query) {
        return Result.ok(adminWalletService.page(query));
    }

    @Operation(summary = "单人钱包档案",
            description = "带最近 10 条流水。该用户从未开过钱包行时 data 为 null——"
                    + "读一个没充过值的号不该顺手往库里插一行，前端按「未开户」空态展示")
    @GetMapping("/{userId}")
    public Result<AdminWalletDTO> detail(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminWalletService.detail(userId));
    }

    @Operation(summary = "账本流水",
            description = "userId 留空即全站检索；按主键倒序（雪花 ID 即时间序）。"
                    + "全站口径单页上限比单人口径更严，因为那张表只有 idx_user_time 一条索引")
    @GetMapping("/tx/page")
    public Result<PageResult<AdminWalletTxDTO>> txPage(AdminWalletTxQueryDTO query) {
        return Result.ok(adminWalletService.pageTransactions(query));
    }

    @Operation(summary = "调整余额",
            description = "delta 为正入账、为负扣减、0 被拒；reason 必填并写进流水备注（含操作人）。"
                    + "不受 im.wallet.recharge-max 约束——那个上限管的是「用户能自己往口袋里塞多少」。"
                    + "返回调整后的最新档案，前端直接刷新那一行")
    @PostMapping("/{userId}/adjust")
    public Result<AdminWalletDTO> adjust(@Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
                                        @Parameter(description = "变动额：正数入账、负数扣减")
                                        @RequestParam("delta") Long delta,
                                        @Parameter(description = "调账事由，必填")
                                        @RequestParam(value = "reason", required = false) String reason) {
        return Result.ok(adminWalletService.adjust(userId, delta, reason, SecurityUtil.getUserId()), "余额已调整");
    }

    @Operation(summary = "礼物目录",
            description = "全量含已下架（后台要能看到并把它重新上架），按 sort 升序；"
                    + "送礼即消耗、无逆向退款通道，所以这里没有价格与库存的写入口")
    @GetMapping("/gifts")
    public Result<List<AdminGiftDTO>> gifts() {
        return Result.ok(adminWalletService.giftCatalog());
    }

    @Operation(summary = "礼物上架 / 下架",
            description = "只改状态：下架只是让用户的礼簿里看不见，历史送礼流水不受影响"
                    + "（那行冗余了礼物名与成交单价）。礼物 ID 不存在回 13003")
    @PostMapping("/gift/{giftId}/status")
    public Result<AdminGiftDTO> giftStatus(@Parameter(description = "礼物 ID") @PathVariable("giftId") Long giftId,
                                          @Parameter(description = "true 上架、false 下架")
                                          @RequestParam("onShelf") boolean onShelf) {
        AdminGiftDTO gift = adminWalletService.setGiftStatus(giftId, onShelf);
        return Result.ok(gift, onShelf ? "礼物已上架" : "礼物已下架");
    }

    @Operation(summary = "送礼流水",
            description = "只读账本（im_gift_record 在 im-live 手里，由本屏装配）。"
                    + "每行带双方昵称与房间标题；用户注销不影响该行出现，房间已清理则标题为 null。"
                    + "那张表只有 idx_room_time / idx_receiver_time，所以单页 50 且建议带时间窗")
    @GetMapping("/gift-record/page")
    public Result<PageResult<AdminGiftRecordDTO>> giftRecordPage(AdminGiftRecordQueryDTO query) {
        return Result.ok(adminWalletService.pageGiftRecords(query));
    }
}
