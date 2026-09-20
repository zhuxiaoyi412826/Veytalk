package com.im.friend.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.im.common.api.Result;
import com.im.common.util.SecurityUtil;
import com.im.friend.dto.req.FriendGroupRequest;
import com.im.friend.dto.req.FriendRemarkRequest;
import com.im.friend.dto.vo.FriendVO;
import com.im.friend.service.FriendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 好友关系接口，全部需要登录。
 *
 * <p>路径 {@code /api/friend/request/**} 由 {@link FriendRequestController} 承载，
 * 与本类的 {@code /api/friend/{friendId}} 系列段数不同，不会产生映射歧义。
 */
@Tag(name = "02-好友", description = "好友列表、备注、分组、删除与拉黑")
@RestController
@RequestMapping("/api/friend")
@RequiredArgsConstructor
@SaCheckLogin
public class FriendController {

    private final FriendService friendService;

    @Operation(summary = "好友列表", description = "含对方资料、在线状态、我的备注与分组；keyword 可按备注/昵称/账号过滤")
    @GetMapping("/list")
    public Result<List<FriendVO>> list(
            @Parameter(description = "搜索关键字，可选") @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(friendService.list(SecurityUtil.getUserId(), keyword));
    }

    @Operation(summary = "好友分组名列表", description = "「默认分组」固定置顶，供前端分组栏渲染")
    @GetMapping("/groups")
    public Result<List<String>> groups() {
        return Result.ok(friendService.listGroups(SecurityUtil.getUserId()));
    }

    @Operation(summary = "黑名单列表", description = "我拉黑的全部好友，存服务端因此多端同步；供集中查看与一键移出")
    @GetMapping("/blacklist")
    public Result<List<FriendVO>> blacklist() {
        return Result.ok(friendService.blacklist(SecurityUtil.getUserId()));
    }

    @Operation(summary = "好友资料卡片", description = "好友视角，带备注与拉黑状态；非好友返回 3001")
    @GetMapping("/{friendId}/card")
    public Result<FriendVO> card(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId) {
        return Result.ok(friendService.detail(SecurityUtil.getUserId(), friendId));
    }

    @Operation(summary = "修改好友备注", description = "remark 留空表示清除备注，回退展示对方昵称")
    @PutMapping("/{friendId}/remark")
    public Result<Void> updateRemark(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId,
                                     @RequestBody @Valid FriendRemarkRequest request) {
        friendService.updateRemark(SecurityUtil.getUserId(), friendId, request.getRemark());
        return Result.ok(null, "备注已更新");
    }

    @Operation(summary = "移动好友分组")
    @PutMapping("/{friendId}/group")
    public Result<Void> updateGroup(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId,
                                    @RequestBody @Valid FriendGroupRequest request) {
        friendService.updateGroup(SecurityUtil.getUserId(), friendId, request.getGroupName());
        return Result.ok(null, "分组已更新");
    }

    @Operation(summary = "删除好友", description = "双向解除关系；会话与历史消息保留，可在会话列表侧单独隐藏")
    @DeleteMapping("/{friendId}")
    public Result<Void> delete(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId) {
        friendService.delete(SecurityUtil.getUserId(), friendId);
        return Result.ok(null, "已删除好友");
    }

    @Operation(summary = "拉黑好友", description = "单向生效，拉黑后双方无法互发消息，对方列表不受影响")
    @PutMapping("/{friendId}/block")
    public Result<Void> block(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId) {
        friendService.block(SecurityUtil.getUserId(), friendId);
        return Result.ok(null, "已加入黑名单");
    }

    @Operation(summary = "取消拉黑")
    @DeleteMapping("/{friendId}/block")
    public Result<Void> unblock(@Parameter(description = "好友用户 ID") @PathVariable("friendId") Long friendId) {
        friendService.unblock(SecurityUtil.getUserId(), friendId);
        return Result.ok(null, "已移出黑名单");
    }
}
