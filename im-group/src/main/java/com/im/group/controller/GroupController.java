package com.im.group.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.PageQuery;
import com.im.common.util.SecurityUtil;
import com.im.group.dto.req.CreateGroupRequest;
import com.im.group.dto.req.GroupNicknameRequest;
import com.im.group.dto.req.MemberIdsRequest;
import com.im.group.dto.req.MuteAllRequest;
import com.im.group.dto.req.MuteMemberRequest;
import com.im.group.dto.req.RoleRequest;
import com.im.group.dto.req.UpdateGroupRequest;
import com.im.group.dto.vo.GroupMemberVO;
import com.im.group.dto.vo.GroupVO;
import com.im.group.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.List;

/**
 * 群组接口，全部需要登录。
 *
 * <p>群内权限（群主 / 管理员 / 成员）不在这里用注解表达，而是由
 * {@link com.im.group.service.GroupPermissionChecker} 在服务层判定：Sa-Token 的权限点是
 * 「用户能不能用这个功能」，群角色是「用户在这个群里能不能管这个人」，两者维度不同，
 * 混在注解里既写不出来也容易让人误以为加了注解就安全了。
 */
@Tag(name = "05-群组", description = "建群、群资料、成员管理、角色、禁言、转让与解散")
@RestController
@RequestMapping("/api/group")
@RequiredArgsConstructor
@SaCheckLogin
public class GroupController {

    private final GroupService groupService;

    @Operation(summary = "创建群组", description = "创建者自动成为群主；memberIds 为初始成员，可为空")
    @SaCheckPermission(ImConstants.PERM_GROUP_CREATE)
    @PostMapping("/create")
    public Result<GroupVO> create(@RequestBody @Valid CreateGroupRequest request) {
        return Result.ok(groupService.create(SecurityUtil.getUserId(), request), "群聊已创建");
    }

    @Operation(summary = "我的群聊", description = "按入群时间倒序，含成员数、我的角色与群会话 ID")
    @GetMapping("/my")
    public Result<List<GroupVO>> my() {
        return Result.ok(groupService.listMine(SecurityUtil.getUserId()));
    }

    @Operation(summary = "群详情", description = "非成员也能看到群名与公告，myRole 等视角字段为空")
    @GetMapping("/{id}")
    public Result<GroupVO> detail(@Parameter(description = "群 ID") @PathVariable("id") Long id) {
        return Result.ok(groupService.detail(SecurityUtil.getUserId(), id));
    }

    @Operation(summary = "修改群资料", description = "群主与管理员可改；字段传 null 表示不改，传空串表示清空（群名不允许清空）")
    @PutMapping("/{id}")
    public Result<GroupVO> update(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                 @RequestBody @Valid UpdateGroupRequest request) {
        return Result.ok(groupService.update(SecurityUtil.getUserId(), id, request), "群资料已更新");
    }

    @Operation(summary = "解散群聊", description = "仅群主可操作；群会话与历史消息保留，最后一条是解散通知")
    @DeleteMapping("/{id}")
    public Result<Void> dismiss(@Parameter(description = "群 ID") @PathVariable("id") Long id) {
        groupService.dismiss(SecurityUtil.getUserId(), id);
        return Result.ok(null, "群聊已解散");
    }

    @Operation(summary = "邀请成员", description = "群主与管理员可操作；已在群内的用户会被跳过，返回实际新增的用户 ID")
    @PostMapping("/{id}/members")
    public Result<List<Long>> addMembers(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                        @RequestBody @Valid MemberIdsRequest request) {
        List<Long> added = groupService.addMembers(SecurityUtil.getUserId(), id, request.getUserIds());
        return Result.ok(added, added.isEmpty() ? "没有新成员加入" : "已邀请 " + added.size() + " 人");
    }

    @Operation(summary = "群成员列表", description = "仅群成员可查看，群主与管理员排在前面；不支持关键字搜索")
    @GetMapping("/{id}/members")
    public Result<PageResult<GroupMemberVO>> members(
            @Parameter(description = "群 ID") @PathVariable("id") Long id,
            @Parameter(description = "页码，从 1 开始") @RequestParam(value = "current", defaultValue = "1") Long current,
            @Parameter(description = "每页条数，最大 100") @RequestParam(value = "size", defaultValue = "20") Long size) {
        PageQuery query = new PageQuery();
        query.setCurrent(current);
        query.setSize(size);
        return Result.ok(groupService.pageMembers(SecurityUtil.getUserId(), id, query));
    }

    @Operation(summary = "移除成员", description = "群主可移除除自己以外的任何人，管理员只能移除普通成员")
    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                    @Parameter(description = "被移除的用户 ID") @PathVariable("userId") Long userId) {
        groupService.removeMember(SecurityUtil.getUserId(), id, userId);
        return Result.ok(null, "已移出群聊");
    }

    @Operation(summary = "设置成员角色", description = "仅群主可操作；只能设为管理员或普通成员，群主需走转让接口")
    @PutMapping("/{id}/members/{userId}/role")
    public Result<Void> updateRole(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                  @Parameter(description = "目标用户 ID") @PathVariable("userId") Long userId,
                                  @RequestBody @Valid RoleRequest request) {
        groupService.updateRole(SecurityUtil.getUserId(), id, userId, request.getRole());
        return Result.ok(null, "角色已更新");
    }

    @Operation(summary = "单人禁言", description = "群主与管理员可操作；minutes 为空表示无限期禁言")
    @PutMapping("/{id}/members/{userId}/mute")
    public Result<Void> muteMember(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                  @Parameter(description = "目标用户 ID") @PathVariable("userId") Long userId,
                                  @RequestBody @Valid MuteMemberRequest request) {
        groupService.muteMember(SecurityUtil.getUserId(), id, userId,
                Boolean.TRUE.equals(request.getMuted()), request.getMinutes());
        return Result.ok(null, Boolean.TRUE.equals(request.getMuted()) ? "已禁言" : "已解除禁言");
    }

    @Operation(summary = "退出群聊", description = "群主需先转让群主，否则返回 6005")
    @PostMapping("/{id}/quit")
    public Result<Void> quit(@Parameter(description = "群 ID") @PathVariable("id") Long id) {
        groupService.quit(SecurityUtil.getUserId(), id);
        return Result.ok(null, "已退出群聊");
    }

    @Operation(summary = "开关全员禁言", description = "群主与管理员可操作，且自身不受全员禁言影响")
    @PutMapping("/{id}/mute-all")
    public Result<Void> muteAll(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                               @RequestBody @Valid MuteAllRequest request) {
        groupService.muteAll(SecurityUtil.getUserId(), id, Boolean.TRUE.equals(request.getMuteAll()));
        return Result.ok(null, Boolean.TRUE.equals(request.getMuteAll()) ? "已开启全员禁言" : "已关闭全员禁言");
    }

    @Operation(summary = "转让群主", description = "仅群主可操作；原群主降级为普通成员并留在群内")
    @PutMapping("/{id}/transfer/{userId}")
    public Result<Void> transfer(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                @Parameter(description = "接任群主的用户 ID") @PathVariable("userId") Long userId) {
        groupService.transfer(SecurityUtil.getUserId(), id, userId);
        return Result.ok(null, "群主已转让");
    }

    @Operation(summary = "修改我的群昵称", description = "留空表示清除，回退展示账号昵称")
    @PutMapping("/{id}/my-nickname")
    public Result<Void> updateMyNickname(@Parameter(description = "群 ID") @PathVariable("id") Long id,
                                        @RequestBody @Valid GroupNicknameRequest request) {
        groupService.updateMyNickname(SecurityUtil.getUserId(), id, request.getNicknameInGroup());
        return Result.ok(null, "群昵称已更新");
    }
}
