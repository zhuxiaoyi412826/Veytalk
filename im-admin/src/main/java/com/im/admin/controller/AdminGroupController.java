package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminGroupService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminGroupDTO;
import com.im.common.domain.AdminGroupMemberQueryDTO;
import com.im.common.domain.AdminGroupQueryDTO;
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

/**
 * 后台的群组管理（四期第五批）。
 *
 * <p>两个写动作，都<b>只跳过「操作人必须是群主/管理员」这一条校验</b>，其余一切
 * （置状态、成员批量退群、会话通知、WebSocket 广播、缓存失效）与群主亲自操作走同一段代码：
 * 管理员解散一个群和群主解散一个群，对群里那 200 个人而言看到的东西必须一模一样——
 * 一条「群已被解散」的系统通知加一个进不去的会话，而不是「后台悄悄把群拔了，成员还在往里发消息」。
 *
 * <p>后台在群里能做的只有这两件事：掐掉一个正在滋事的群，或者让除了组织者以外的所有人闭嘴。
 * <b>没有</b>改群名、换群主、踢某个成员、设管理员——那些是群内治理，是群主的权利；
 * {@code system:group:manage} 的授权理由是「阻断」，不是「接管」。
 *
 * <p>强制解散不可逆（成员关系要重新生成，那是造数据而不是撤销），所以列表与档案都
 * <b>必须把已解散的群也显示出来</b>（{@code query.status} 可筛），否则「这个群什么时候没的」就查不到了。
 * 全员禁言可逆，因此比解散轻；它挡不住群主与管理员（沿用群内禁言的既有豁免，后台不重新发明）。
 *
 * <p>响应里绝不出现 {@code inviteCode}，机器人项里绝不出现 {@code webhookToken}：
 * 前者是入群凭证，后者是外部系统代发群消息的凭证，拿到任何一个都能绕过正常关系链做事。
 */
@Tag(name = "16-管理后台", description = "群组管理：群台账、成员与强制解散 / 全员禁言")
@RestController
@RequestMapping("/api/admin/group")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_GROUP_MANAGE)
public class AdminGroupController {

    private final AdminGroupService adminGroupService;

    @Operation(summary = "群台账",
            description = "默认含已解散的群，按群 ID（雪花）倒序=新建的在前。keyword 是 LIKE '%kw%'，"
                    + "而 im_group 只有 idx_owner 一条二级索引，所以按名字搜群是全表扫描 + filesort，"
                    + "单页上限与关键词长度都由服务端夹死。每行的成员数一次 GROUP BY 批量补")
    @GetMapping("/page")
    public Result<PageResult<AdminGroupDTO>> page(AdminGroupQueryDTO query) {
        return Result.ok(adminGroupService.page(query));
    }

    @Operation(summary = "单群档案",
            description = "群信息 + 最近成员快照 + 最近入群申请（含已处理）+ 机器人（含已停用）；"
                    + "条数全部由服务端夹死，真分页走成员页签。群不存在回 6001")
    @GetMapping("/{groupId}")
    public Result<AdminGroupDTO> detail(@Parameter(description = "群 ID") @PathVariable("groupId") Long groupId) {
        return Result.ok(adminGroupService.detail(groupId));
    }

    @Operation(summary = "群成员分页",
            description = "只给在群成员（退群历史本批不开开关）；入群时间正序，群主与管理员靠角色提前——"
                    + "这一屏要看的是「这个群的组织者是谁、他被禁言了没有」。"
                    + "keyword 同时匹配群内昵称与 im_user 的昵称/账号（后者先经 UserQuerySpi 把名字变成 ID）")
    @GetMapping("/member/page")
    public Result<PageResult<AdminGroupDTO.MemberItem>> memberPage(AdminGroupMemberQueryDTO query) {
        return Result.ok(adminGroupService.pageMembers(query));
    }

    @Operation(summary = "强制解散",
            description = "等价于群主亲自解散但不查操作人群内角色：成员全部置为已退群、会话进不去、"
                    + "历史消息仍留在库里。<b>不可逆</b>。对已解散的群重复调用幂等返回当前档案，"
                    + "所以连点两次不会出现「第二次失败所以不知道到底散没散」")
    @PostMapping("/{groupId}/dismiss")
    public Result<AdminGroupDTO> dismiss(@Parameter(description = "群 ID") @PathVariable("groupId") Long groupId) {
        return Result.ok(adminGroupService.forceDismiss(groupId, SecurityUtil.getUserId()), "群组已强制解散");
    }

    @Operation(summary = "全员禁言",
            description = "muteAll=true 开启、false 解除，可逆。挡的是除群主与管理员以外的所有人"
                    + "（豁免规则沿用群内禁言，后台不重新发明一遍），所以禁一个滋事的群"
                    + "不等于禁掉那个群里最嗓子的两个人")
    @PostMapping("/{groupId}/mute-all")
    public Result<AdminGroupDTO> muteAll(@Parameter(description = "群 ID") @PathVariable("groupId") Long groupId,
                                        @Parameter(description = "true 开启全员禁言、false 解除")
                                        @RequestParam("muteAll") boolean muteAll) {
        AdminGroupDTO group = adminGroupService.forceMuteAll(groupId, muteAll, SecurityUtil.getUserId());
        return Result.ok(group, muteAll ? "已开启全员禁言" : "已解除全员禁言");
    }
}
