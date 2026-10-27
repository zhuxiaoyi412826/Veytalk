package com.im.admin.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.im.admin.service.AdminRemoteService;
import com.im.common.api.PageResult;
import com.im.common.api.Result;
import com.im.common.constant.ImConstants;
import com.im.common.domain.AdminRemoteAuditDTO;
import com.im.common.domain.AdminRemoteAuditQueryDTO;
import com.im.common.domain.AdminRemoteOverviewDTO;
import com.im.common.domain.AdminRemoteSessionDTO;
import com.im.common.domain.AdminRemoteSessionQueryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的远程控制审计（四期第二批）。
 *
 * <p>四个端点<b>全是 GET</b>，一个写动作都没有：这一屏答的是「谁在什么时候控了谁的哪台机器、
 * 在那台机器上做了什么」，读两张已有表就够了。不做「强制中断会话」按钮的理由写在
 * {@link com.im.common.spi.AdminRemoteSpi} 的类注释里，其中一条值得在这里也留一句：
 * <b>禁用账号断不掉进行中的远程会话</b>（kickOut 只管聊天长连接，远程两条通道是握手期鉴权），
 * 所以「先封号再说」在这条链路上不是止损手段，别把它当成。
 *
 * <p>权限单设 {@code system:remote} 而不并进看板或直播：审计流水的 {@code detail} 里是
 * 别人机器上的文件路径与命令行原文，敏感度和日志页同一级。
 *
 * <p>响应里绝不出现 accessCode / ticket / aesKey：那些是接入凭证与密钥，
 * 台账只需要设备名与机器指纹，多带一个就等于多发一把钥匙。
 */
@Tag(name = "16-管理后台", description = "远程控制审计：会话台账与操作流水（只读）")
@RestController
@RequestMapping("/api/admin/remote")
@RequiredArgsConstructor
@SaCheckLogin
@SaCheckPermission(ImConstants.PERM_SYSTEM_REMOTE)
public class AdminRemoteController {

    private final AdminRemoteService adminRemoteService;

    @Operation(summary = "会话台账",
            description = "全站远程会话分页，含已拒绝与已结束的；进行中的恒排最前。"
                    + "keyword 只匹配设备标识与设备名（用户维度用 userId），每页顺带补两端昵称、"
                    + "设备名、审计条数与两个实时态（agentOnline / relaying）")
    @GetMapping("/page")
    public Result<PageResult<AdminRemoteSessionDTO>> page(AdminRemoteSessionQueryDTO query) {
        return Result.ok(adminRemoteService.page(query));
    }

    @Operation(summary = "单会话档案",
            description = "进行中会话的 bytes 取内存中继累计值（该列只在收尾时落库，照读会一直是 0）；"
                    + "会话 ID 不存在回 9005")
    @GetMapping("/{sessionId}")
    public Result<AdminRemoteSessionDTO> detail(
            @Parameter(description = "远程会话 ID") @PathVariable("sessionId") Long sessionId) {
        return Result.ok(adminRemoteService.detail(sessionId));
    }

    @Operation(summary = "审计流水",
            description = "按 (createTime, id) 倒序；sessionId 为空即跨会话全局检索——那张表只有 "
                    + "idx_session_time 一条索引，全局检索因此单页上限 50 且页面不做轮询。"
                    + "dangerOnly 的高危词表在服务端，前端不复制一份")
    @GetMapping("/audit/page")
    public Result<PageResult<AdminRemoteAuditDTO>> auditPage(AdminRemoteAuditQueryDTO query) {
        return Result.ok(adminRemoteService.pageAudits(query));
    }

    @Operation(summary = "概览数字",
            description = "进行中 / 待授权会话数、今日新建会话数、当前实例在线 Agent 数，带 generatedAt。"
                    + "后端无缓存，本页也不轮询它——手点刷新")
    @GetMapping("/overview")
    public Result<AdminRemoteOverviewDTO> overview() {
        return Result.ok(adminRemoteService.overview());
    }
}
