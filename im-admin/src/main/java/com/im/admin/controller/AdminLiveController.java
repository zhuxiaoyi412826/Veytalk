package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminLiveService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminLiveDTO;
import com.im.common.domain.AdminLiveQueryDTO;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的直播管理（三期）。
 *
 * <p>只有「看」与「掐」两个动作，<b>没有删房间、没有解封、更没有开播</b>：
 * 场次表是处置台账，删一行等于抹掉一次直播存在过的证据；BANNED 是终态，
 * 解封等于承诺「可以原样续播」，而 streamKey 已作废、分片已过期，那是个说谎的按钮；
 * 开播永远是主播侧动作，管理员替人开播在权限模型里找不到正当性。
 *
 * <p>强制关播复用举报处置同一条 ban 路径（弹幕中断、心跳清理、回放入口删除
 * 全在 {@code LiveRoomService#stop} 里闭环），响应带回处置后的最新档案，
 * 前端直接刷新那一行，不需要二次查询猜结果。
 */
@Tag(name = "16-管理后台", description = "直播管理：场次台账与强制关播")
@RestController
@RequestMapping("/api/admin/live")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_LIVE_MANAGE)
public class AdminLiveController {

    private final AdminLiveService adminLiveService;

    @Operation(summary = "场次列表",
            description = "含全部历史与已封禁场次（不受用户端大厅的 1 小时隐藏规则约束）；"
                    + "直播中的恒排最前，其余按开播时间倒序；响应绝不含 streamKey")
    @GetMapping("/page")
    public Result<PageResult<AdminLiveDTO>> page(AdminLiveQueryDTO query) {
        return Result.ok(adminLiveService.page(query));
    }

    @Operation(summary = "单场档案", description = "房间不存在（含被主播撤销的真删）返回 11003")
    @GetMapping("/{roomId}")
    public Result<AdminLiveDTO> detail(@Parameter(description = "房间 ID") @PathVariable("roomId") Long roomId) {
        return Result.ok(adminLiveService.detail(roomId));
    }

    @Operation(summary = "强制关播",
            description = "以 ban 原因中断直播：断弹幕通道、清心跳与门禁、删回放入口，主播需重新开播才能再来；"
                    + "对已结束的场次幂等跳过，返回当前档案。无解封接口——封禁是终态")
    @PostMapping("/{roomId}/stop")
    public Result<AdminLiveDTO> stop(@Parameter(description = "房间 ID") @PathVariable("roomId") Long roomId) {
        AdminLiveDTO room = adminLiveService.forceStop(roomId, SecurityUtil.getUserId());
        String msg = room != null && Integer.valueOf(1).equals(room.getStatus())
                ? "关播指令未生效，请刷新后重试" : "直播已中断";
        return Result.ok(room, msg);
    }
}
