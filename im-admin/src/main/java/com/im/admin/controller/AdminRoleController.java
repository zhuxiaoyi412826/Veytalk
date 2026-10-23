package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.dto.req.UserRoleAssignRequest;
import com.im.admin.dto.vo.AdminRoleVO;
import com.im.admin.service.AdminRoleService;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台的角色分配。
 *
 * <p>整个类要求 {@code system:role:manage} 权限点（二期新增）：能「给用户配角色」等于能把一个
 * 普通人提权成管理员，这是后台里最危险的一把钥匙，因此单独授权、只给 admin（伞权限持有者）
 * 与运营（operator）。「列角色」「查某人有哪些角色」都是只读，唯独 {@code PUT .../user/{userId}}
 * 会改权限，改完由 im-user 侧失效该用户的权限缓存、即时生效。
 *
 * <p>三条护栏（不能改自己的角色、不能摘掉最后一个可用管理员、不接受未知/停用角色）落在
 * {@code AdminUserSpiImpl}，不在这一层重复——护栏必须对所有调用路径生效，放进控制器只管得住这一个入口。
 */
@Tag(name = "16-管理后台", description = "角色分配：列出角色、查看与重设用户角色")
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_ROLE_MANAGE)
public class AdminRoleController {

    private final AdminRoleService adminRoleService;

    @Operation(summary = "可分配角色列表", description = "启用且未删除的角色，给「配角色」下拉用")
    @GetMapping("/list")
    public Result<List<AdminRoleVO>> list() {
        return Result.ok(adminRoleService.listRoles());
    }

    @Operation(summary = "某用户的角色", description = "该用户当前持有的角色对象列表，界面据此勾选复选框")
    @GetMapping("/user/{userId}")
    public Result<List<AdminRoleVO>> userRoles(
            @Parameter(description = "用户 ID") @PathVariable("userId") Long userId) {
        return Result.ok(adminRoleService.userRoles(userId));
    }

    @Operation(summary = "重设用户角色",
            description = "整体替换语义：传入的角色集合就是该用户最终应持有的角色，空数组=清空全部后台角色。"
                    + "不能改自己的角色，不能把最后一个可用管理员的 admin 摘掉；改完即时生效（失效权限缓存）")
    @PutMapping("/user/{userId}")
    public Result<List<AdminRoleVO>> assign(
            @Parameter(description = "用户 ID") @PathVariable("userId") Long userId,
            @RequestBody @Valid UserRoleAssignRequest request) {
        return Result.ok(adminRoleService.assign(userId, request.getRoleIds(), SecurityUtil.getUserId()),
                "角色已更新");
    }
}
