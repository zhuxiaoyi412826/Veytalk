package com.im.user.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.api.ResultCode;
import com.im.common.exception.BusinessException;
import com.im.common.security.ratelimit.RateLimit;
import com.im.common.util.SecurityUtil;
import com.im.user.dto.vo.LoginDeviceVO;
import com.im.user.dto.vo.LoginRecordVO;
import com.im.user.entity.LoginLog;
import com.im.user.service.DeviceService;
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

import java.util.List;
import java.util.Map;

/**
 * 登录设备管理，全部需要登录，且<b>只能管自己的设备</b>。
 *
 * <p>userId 一律取当前登录态，不从入参收：一旦开了「传别人的 userId 看他的设备」这个口，
 * IP + UA + 登录时间合起来就是一份行踪记录。后台要看别人的设备得另开一套带审计的接口。
 *
 * <p>这些端点不出现任何「踢人」以外的破坏性动作：撤销设备只是注销会话并标一行状态，
 * 真要禁止登录是后台封号的职责（{@code im_user.status}），两套语义不混用。
 */
@Tag(name = "01-设备", description = "登录设备列表、撤销、下线其他设备与账号安全记录")
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
@SaCheckLogin
public class DeviceController {

    /** 登录记录一屏默认条数，上限由服务层再夹一次 */
    private static final int RECORD_DEFAULT = 20;

    private final DeviceService deviceService;

    @Operation(summary = "我的登录设备",
            description = "按最近登录时间倒序。current=true 是本机（前端应禁用它的撤销按钮）；"
                    + "identifiable=false 表示老客户端只上送了类型码，撤销它会把同类型的所有会话一起下线")
    @GetMapping("/list")
    public Result<List<LoginDeviceVO>> list() {
        return Result.ok(deviceService.listByUser(SecurityUtil.getUserId()));
    }

    @Operation(summary = "我的登录记录",
            description = "登录 / 注销 / 被顶号 / 撤销设备 / 凭证变更的流水，按发生时间倒序；"
                    + "只记 IP 不记城市（服务端没有接入任何归属地库，编不出城市）")
    @GetMapping("/records")
    public Result<List<LoginRecordVO>> records(@Parameter(description = "取多少条，最多 50")
                                               @RequestParam(value = "limit", required = false) Integer limit) {
        int size = limit == null || limit <= 0 ? RECORD_DEFAULT : Math.min(limit, 50);
        return Result.ok(deviceService.listRecords(SecurityUtil.getUserId(), size));
    }

    @Operation(summary = "撤销一台设备",
            description = "注销它的会话、断掉它的长连接并标记为已撤销；不禁止下次登录——"
                    + "能凭密码重新登进来就说明身份成立，那时该行会自动回到在用")
    @RateLimit(count = 10, key = "device.revoke")
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable("id") Long id) {
        boolean done = deviceService.revoke(SecurityUtil.getUserId(), id);
        if (!done) {
            throw new BusinessException(ResultCode.LOGIN_DEVICE_NOT_FOUND);
        }
        return Result.ok(null, "该设备已下线");
    }

    @Operation(summary = "下线其他设备",
            description = "除当前这台以外全部踢下线。它同时是「我在外面登过，但我记不清在哪几台」的兜底按钮")
    @RateLimit(count = 3, key = "device.logout-others")
    @PostMapping("/logout-others")
    public Result<Map<String, Object>> logoutOthers() {
        Long userId = SecurityUtil.getUserId();
        int count = deviceService.logoutOthers(userId, "已在其他设备上执行「下线其他设备」", LoginLog.EVENT_LOGOUT);
        return Result.ok(Map.of("count", count), count > 0 ? "已下线 " + count + " 台其他设备" : "没有其他设备在线");
    }
}
