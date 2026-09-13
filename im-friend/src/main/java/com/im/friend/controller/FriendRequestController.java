package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.req.FriendApplyRequest;
import com.im.friend.dto.req.FriendRequestQuery;
import com.im.friend.dto.vo.FriendRequestVO;
import com.im.friend.service.FriendRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 好友申请接口。
 */
@Tag(name = "02-好友申请", description = "发起申请、查看收到与发出的申请、同意或拒绝")
@RestController
@RequestMapping("/api/friend/request")
@RequiredArgsConstructor
@SaCheckLogin
public class FriendRequestController {

    private final FriendRequestService friendRequestService;

    @Operation(summary = "发起好友申请",
            description = "targetUserId 与 targetAccount 二选一；重复的待处理申请返回 3004，被对方拉黑返回 3006")
    @SaCheckPermission(ImConstants.PERM_FRIEND_APPLY)
    @PostMapping("/apply")
    public Result<FriendRequestVO> apply(@RequestBody @Valid FriendApplyRequest request) {
        return Result.ok(friendRequestService.apply(SecurityUtil.getUserId(), request), "申请已发送");
    }

    @Operation(summary = "我收到的申请", description = "按申请时间倒序分页，status 可过滤；actionable 为 true 才允许同意/拒绝")
    @GetMapping("/received")
    public Result<PageResult<FriendRequestVO>> received(@Valid FriendRequestQuery query) {
        return Result.ok(friendRequestService.listReceived(SecurityUtil.getUserId(), query));
    }

    @Operation(summary = "我发出的申请", description = "按申请时间倒序分页，用于查看对方是否已处理")
    @GetMapping("/sent")
    public Result<PageResult<FriendRequestVO>> sent(@Valid FriendRequestQuery query) {
        return Result.ok(friendRequestService.listSent(SecurityUtil.getUserId(), query));
    }

    @Operation(summary = "待处理申请数", description = "供前端「好友申请」入口显示红点")
    @GetMapping("/pending-count")
    public Result<Long> pendingCount() {
        return Result.ok(friendRequestService.countPending(SecurityUtil.getUserId()));
    }

    @Operation(summary = "同意申请",
            description = "双向建立好友关系并创建单聊会话，返回会话 ID 供前端直接跳转聊天窗口")
    @PutMapping("/{id}/accept")
    public Result<Long> accept(@Parameter(description = "申请 ID") @PathVariable("id") Long id) {
        return Result.ok(friendRequestService.accept(SecurityUtil.getUserId(), id), "已同意好友申请");
    }

    @Operation(summary = "拒绝申请")
    @PutMapping("/{id}/reject")
    public Result<Void> reject(@Parameter(description = "申请 ID") @PathVariable("id") Long id) {
        friendRequestService.reject(SecurityUtil.getUserId(), id);
        return Result.ok(null, "已拒绝好友申请");
    }
}
